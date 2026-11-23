package com.example.app.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fails open: while Redis is unreachable or slower than {@code app.rate-limit.backend-timeout}, requests
 * pass, since a cache outage must not lock users out.
 *
 * <p>Once connected, Lettuce reconnects by itself. Until then the attempts run on a thread of their own: one
 * can block for {@code spring.data.redis.connect-timeout}, and must not delay a request or the scheduled
 * jobs.</p>
 */
@Component
@Slf4j
public class RedisRateLimiter implements DisposableBean {

    static final String LET_THROUGH_METRIC = "rate_limit.unchecked";
    static final String RECONNECT_FAILURE_METRIC = "rate_limit.reconnect.failures";
    private static final String KEY_PREFIX = "rate-limit:";
    private static final RedisCodec<String, byte[]> CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    private final LettuceConnectionFactory connectionFactory;
    private final RateLimitProperties properties;
    private final Counter letThrough;
    private final Counter reconnectFailures;
    private final AtomicBoolean backendAvailable = new AtomicBoolean(true);
    private final ScheduledExecutorService reconnector = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("rate-limit-reconnect").daemon(true).factory());

    private volatile StatefulConnection<String, byte[]> connection;
    private volatile ProxyManager<String> buckets;

    // Hand-written: the counters are registered at startup, so their series read 0 before the first outage.
    public RedisRateLimiter(LettuceConnectionFactory connectionFactory, RateLimitProperties properties,
                            MeterRegistry meterRegistry) {
        this.connectionFactory = connectionFactory;
        this.properties = properties;
        this.letThrough = Counter.builder(LET_THROUGH_METRIC)
                .description("Requests let through without a rate-limit check because Redis was unavailable")
                .register(meterRegistry);
        this.reconnectFailures = Counter.builder(RECONNECT_FAILURE_METRIC)
                .description("Failed background attempts to connect the rate limiter to Redis")
                .register(meterRegistry);
    }

    public void acquire(String key, RateLimit limit) {
        ConsumptionProbe probe;
        try {
            probe = currentBuckets().builder()
                    .build(KEY_PREFIX + key, () -> configurationFor(limit))
                    .tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException backendFailure) {
            letThrough.increment();
            onBackendFailure(backendFailure);
            return;
        }
        onBackendSuccess();
        if (!probe.isConsumed()) {
            throw new RateLimitExceededException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }

    @EventListener(ApplicationStartedEvent.class)
    void connectAtStartup() {
        connectIfNeeded();
        long interval = properties.reconnectInterval().toMillis();
        reconnector.scheduleWithFixedDelay(this::connectIfNeeded, interval, interval, TimeUnit.MILLISECONDS);
    }

    void connectIfNeeded() {
        if (!properties.enabled() || Objects.nonNull(buckets)) {
            return;
        }
        try {
            connect();
            log.info("Rate limiter connected to Redis");
        } catch (RuntimeException connectFailure) {
            reconnectFailures.increment();
            onBackendFailure(connectFailure);
        }
    }

    private synchronized void connect() {
        if (Objects.nonNull(buckets)) {
            return;
        }
        AbstractRedisClient client = connectionFactory.getNativeClient();
        if (Objects.isNull(client)) {
            throw new IllegalStateException("The Redis connection factory has no client yet");
        }
        ExpirationAfterWriteStrategy expiry =
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(properties.expiryGrace());
        if (client instanceof RedisClusterClient cluster) {
            StatefulRedisClusterConnection<String, byte[]> clusterConnection = cluster.connect(CODEC);
            connection = clusterConnection;
            buckets = Bucket4jLettuce.casBasedBuilder(clusterConnection)
                    .expirationAfterWrite(expiry)
                    .requestTimeout(properties.backendTimeout())
                    .build();
        } else if (client instanceof RedisClient standalone) {
            StatefulRedisConnection<String, byte[]> standaloneConnection = standalone.connect(CODEC);
            connection = standaloneConnection;
            buckets = Bucket4jLettuce.casBasedBuilder(standaloneConnection)
                    .expirationAfterWrite(expiry)
                    .requestTimeout(properties.backendTimeout())
                    .build();
        } else {
            throw new IllegalStateException("Unsupported Redis client " + client.getClass().getName());
        }
    }

    private ProxyManager<String> currentBuckets() {
        ProxyManager<String> current = buckets;
        if (Objects.isNull(current)) {
            throw new IllegalStateException("Rate limiter not connected to Redis yet");
        }
        return current;
    }

    private static BucketConfiguration configurationFor(RateLimit limit) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.refillPeriod())
                        .build())
                .build();
    }

    private void onBackendFailure(RuntimeException failure) {
        if (backendAvailable.compareAndSet(true, false)) {
            log.warn("Redis unavailable: requests are NOT rate limited until it recovers: {}", failure.toString());
        }
    }

    private void onBackendSuccess() {
        if (backendAvailable.compareAndSet(false, true)) {
            log.info("Redis available again: rate limiting resumed");
        }
    }

    @Override
    public void destroy() {
        reconnector.shutdownNow();
        StatefulConnection<String, byte[]> current = connection;
        if (Objects.nonNull(current)) {
            current.close();
        }
    }
}
