package com.example.app.user.service;

import com.example.app.common.security.SessionVersions;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
// tenancy:begin
// tenancy:else
import java.util.Objects;
// tenancy:end
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * While Redis is down the number is read from the database, one read by primary key per request: accepting the
 * token would reopen the window a revocation closed, and refusing it would sign everyone out on a Redis blip.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class SessionVersionService implements SessionVersions {

    // tenancy:begin
    // /** A tenant token is for one membership; a member removed and added again is a new one, whose number no older token carries. */
    // static final String MEMBERSHIP_CLAIM = "membership_id";
    // tenancy:end
    static final String CIRCUIT_BREAKER = "session-version-cache";
    static final String FALLBACK_METRIC = "session_version.cache.fallbacks";
    static final String LOST_PUBLISH_METRIC = "session_version.publish.failures";
    private static final String KEY_PREFIX = "sv:";

    // tenancy:begin
    // private final MembershipService memberships;
    // tenancy:else
    private final UserService users;
    // tenancy:end
    private final StringRedisTemplate redis;
    private final SessionVersionProperties properties;
    private final CircuitBreaker cache;
    private final Counter fallbacks;
    private final Counter lostPublishes;
    private final AtomicBoolean cacheAvailable = new AtomicBoolean(true);

    // Hand-written: the circuit breaker is looked up once (a missing instance stops the startup) and the counters
    // registered, so their series read 0 before the first fallback.
    SessionVersionService(
                          // tenancy:begin
                          // MembershipService memberships,
                          // tenancy:else
                          UserService users,
                          // tenancy:end
                          StringRedisTemplate redis, SessionVersionProperties properties,
                          CircuitBreakerRegistry circuitBreakers, MeterRegistry meterRegistry) {
        // tenancy:begin
        // this.memberships = memberships;
        // tenancy:else
        this.users = users;
        // tenancy:end
        this.redis = redis;
        this.properties = properties;
        this.cache = circuitBreakers.find(CIRCUIT_BREAKER).orElseThrow(() -> new IllegalStateException(
                "The session version needs resilience4j.circuitbreaker.instances.%s".formatted(CIRCUIT_BREAKER)));
        this.fallbacks = Counter.builder(FALLBACK_METRIC)
                .description("Session version checks answered from the database because Redis was unavailable")
                .register(meterRegistry);
        this.lostPublishes = Counter.builder(LOST_PUBLISH_METRIC)
                .description("Raised session versions that didn't reach Redis: the old one stays accepted up to the cache TTL")
                .register(meterRegistry);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean isCurrent(Jwt accessToken) {
        if (!(accessToken.getClaims().get(CLAIM) instanceof Number claimed)) {
            return false;
        }
        // tenancy:begin
        // if (!(accessToken.getClaims().get(MEMBERSHIP_CLAIM) instanceof String membershipId)) {
        //     return false;
        // }
        // UUID id = UUID.fromString(membershipId);
        // tenancy:else
        String subject = accessToken.getSubject();
        if (Objects.isNull(subject)) {
            return false;
        }
        UUID id = UUID.fromString(subject);
        // tenancy:end
        long tokenVersion = claimed.longValue();
        Optional<String> cached;
        try {
            cached = Optional.ofNullable(cache.executeSupplier(() -> redis.opsForValue().get(key(id))));
        } catch (RuntimeException unavailable) {
            onCacheFailure(unavailable);
            fallbacks.increment();
            return isStored(tokenVersion, id);
        }
        onCacheSuccess();
        if (cached.isEmpty()) {
            Optional<Integer> stored = storedVersion(id);
            stored.ifPresent(version -> fillIfAbsent(id, version));
            return stored.filter(version -> version == tokenVersion).isPresent();
        }
        long cachedVersion = Long.parseLong(cached.get());
        // Numbers only go up: Redis missed a publish, and the database decides.
        if (tokenVersion > cachedVersion) {
            return isStored(tokenVersion, id);
        }
        return tokenVersion == cachedVersion;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void publish(SessionVersionsRaised raised) {
        for (Map.Entry<UUID, Integer> version : raised.versions().entrySet()) {
            write(version.getKey(), version.getValue());
        }
    }

    /** A miss's fill: SET NX, so a bump published after the database read keeps its newer number. */
    void fillIfAbsent(UUID id, int version) {
        try {
            cache.executeRunnable(() -> redis.opsForValue().setIfAbsent(key(id), Integer.toString(version), properties.cacheTtl()));
        } catch (RuntimeException unavailable) {
            onCacheFailure(unavailable);
        }
    }

    static String key(UUID id) {
        return KEY_PREFIX + id;
    }

    private void write(UUID id, int version) {
        try {
            cache.executeRunnable(() -> redis.opsForValue().set(key(id), Integer.toString(version), properties.cacheTtl()));
        } catch (RuntimeException unavailable) {
            onCacheFailure(unavailable);
            lostPublishes.increment();
            log.warn("Session version not written to Redis, the older one is accepted for up to {}: id={}, sv={}",
                    properties.cacheTtl(), id, version);
        }
    }

    private Optional<Integer> storedVersion(UUID id) {
        // tenancy:begin
        // return memberships.findSessionVersion(id);
        // tenancy:else
        return users.findSessionVersion(id);
        // tenancy:end
    }

    private boolean isStored(long tokenVersion, UUID id) {
        return storedVersion(id).filter(version -> version == tokenVersion).isPresent();
    }

    private void onCacheFailure(RuntimeException failure) {
        if (cacheAvailable.compareAndSet(true, false)) {
            log.warn("Redis unavailable: session versions are read from the database until it recovers: {}", failure.toString());
        }
    }

    private void onCacheSuccess() {
        if (cacheAvailable.compareAndSet(false, true)) {
            log.info("Redis available again: session versions are read from it");
        }
    }
}
