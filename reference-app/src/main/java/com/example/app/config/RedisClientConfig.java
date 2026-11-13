package com.example.app.config;

import io.lettuce.core.ClientOptions;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Lettuce bounded for a fail-open client. By default it buffers every command while disconnected
 * and replays them on reconnect, in an unbounded queue: during an outage that is memory growing per
 * request, and checks answered long after their request was let through. Refused at once instead,
 * the rate limiter fails open immediately.
 *
 * <p>A client-options customizer, not a whole {@code LettuceClientConfigurationBuilderCustomizer}
 * setting new {@code ClientOptions}: it adds to the options Boot builds, so
 * {@code spring.data.redis.connect-timeout} (a socket option) still applies.</p>
 */
@Configuration
class RedisClientConfig {

    @Bean
    LettuceClientOptionsBuilderCustomizer boundedRedisClient(Settings settings) {
        return options -> options
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(settings.requestQueueSize());
    }

    @Validated
    @ConfigurationProperties(prefix = "app.redis")
    record Settings(@Positive int requestQueueSize) {
    }
}
