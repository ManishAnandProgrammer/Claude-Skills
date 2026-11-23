package com.example.app.user.service;

import com.example.app.common.security.SessionVersions;
import com.example.app.support.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionVersionServiceIntegrationTest extends IntegrationTest {

    @Autowired
    private SessionVersionService sessionVersions;

    @Autowired
    private UserService users;

    @Autowired
    private TokenService tokens;

    @Autowired
    private SessionVersionProperties properties;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private int storedVersion(UUID userId) {
        return jdbc.queryForObject("select session_version from users where id = ?", Integer.class, userId);
    }

    private String cached(UUID userId) {
        return redis.opsForValue().get(SessionVersionService.key(userId));
    }

    private void signOutEverywhere(UUID userId) {
        tokens.endAllSessions(userId);
    }

    private Jwt decoded(String accessToken) {
        return jwtDecoder.decode(accessToken);
    }

    @Test
    @DisplayName("A miss reads the database and fills Redis, with the configured TTL")
    void aMissFillsRedis() throws Exception {
        TestUser user = newUser();
        redis.delete(SessionVersionService.key(user.id()));

        assertThat(sessionVersions.isCurrent(decoded(user.accessToken()))).isTrue();

        assertThat(cached(user.id())).isEqualTo("0");
        assertThat(redis.getExpire(SessionVersionService.key(user.id()))).isBetween(1L, properties.cacheTtl().toSeconds());
    }

    @Test
    @DisplayName("""
            The fill race: a request read 0 from the database, a bump commits and publishes 1, then the request's \
            late fill (SET NX) loses: Redis still says 1. Written over instead, the revoked token would pass""")
    void aLateFillLosesToTheBump() throws Exception {
        TestUser user = newUser();
        Jwt revoked = decoded(user.accessToken());
        redis.delete(SessionVersionService.key(user.id()));
        int readBeforeTheBump = storedVersion(user.id());

        signOutEverywhere(user.id());
        sessionVersions.fillIfAbsent(user.id(), readBeforeTheBump);

        assertThat(cached(user.id())).isEqualTo(Integer.toString(readBeforeTheBump + 1));
        assertThat(sessionVersions.isCurrent(revoked)).isFalse();
        // What the guard prevents: a plain SET of the late read puts the old number back, and the revoked token passes.
        redis.opsForValue().set(SessionVersionService.key(user.id()), Integer.toString(readBeforeTheBump));
        assertThat(sessionVersions.isCurrent(revoked)).isTrue();
    }

    @Test
    @DisplayName("A rolled-back access change raises nothing and leaves Redis alone; the same change committed raises both")
    void aRolledBackChangeRaisesNothing() throws Exception {
        TestUser user = newUser();
        assertThat(sessionVersions.isCurrent(decoded(user.accessToken()))).isTrue();
        assertThat(cached(user.id())).isEqualTo("0");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            tokens.endAllSessions(user.id());
            status.setRollbackOnly();
        });

        assertThat(storedVersion(user.id())).isZero();
        assertThat(cached(user.id())).isEqualTo("0");
        assertThat(sessionVersions.isCurrent(decoded(user.accessToken()))).isTrue();
        signOutEverywhere(user.id());
        assertThat(storedVersion(user.id())).isOne();
        assertThat(cached(user.id())).isEqualTo("1");
    }

    @Test
    @DisplayName("A token newer than Redis's copy (a publish that was lost) is decided by the database: current, so accepted")
    void aTokenNewerThanTheCacheAsksTheDatabase() throws Exception {
        TestUser user = newUser();
        signOutEverywhere(user.id());
        Jwt current = decoded(signIn(user).accessToken());
        redis.opsForValue().set(SessionVersionService.key(user.id()), "0");

        assertThat(sessionVersions.isCurrent(current)).isTrue();
    }

    @Test
    @DisplayName("A token without the sv claim, or for an account that doesn't exist, is not current")
    void aTokenWithoutAVersionIsNotCurrent() throws Exception {
        TestUser user = newUser();
        Jwt withoutVersion = Jwt.withTokenValue("token").header("alg", "HS256").subject(user.id().toString()).build();
        Jwt unknownAccount = Jwt.withTokenValue("token").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .claim(SessionVersions.CLAIM, 0).build();

        assertThat(sessionVersions.isCurrent(withoutVersion)).isFalse();
        assertThat(sessionVersions.isCurrent(unknownAccount)).isFalse();
        assertThat(sessionVersions.isCurrent(decoded(user.accessToken()))).isTrue();
    }

    @Test
    @DisplayName("""
            Redis down: every check reads the database instead, so a revoked token is still refused and a current \
            one accepted, through the open circuit breaker too; each such check is counted""")
    void redisDownFallsBackToTheDatabase() throws Exception {
        TestUser user = newUser();
        Jwt revoked = decoded(user.accessToken());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).build());
        breakers.circuitBreaker(SessionVersionService.CIRCUIT_BREAKER);
        LettuceConnectionFactory unreachable = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", closedPort()));
        unreachable.afterPropertiesSet();
        unreachable.start();
        try {
            SessionVersionService withoutRedis = new SessionVersionService(users, new StringRedisTemplate(unreachable),
                    properties, breakers, meters);

            assertThat(withoutRedis.isCurrent(revoked)).isTrue();
            signOutEverywhere(user.id());
            assertThat(withoutRedis.isCurrent(revoked)).isFalse();
            assertThat(breakers.circuitBreaker(SessionVersionService.CIRCUIT_BREAKER).getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(withoutRedis.isCurrent(decoded(signIn(user).accessToken()))).isTrue();
            assertThat(meters.counter(SessionVersionService.FALLBACK_METRIC).count()).isEqualTo(3);
        } finally {
            unreachable.destroy();
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
