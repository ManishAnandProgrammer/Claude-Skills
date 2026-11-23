package com.example.app.config;

import io.lettuce.core.ClientOptions;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * By default Lettuce buffers every command while disconnected, in an unbounded queue, and replays them on
 * reconnect: during an outage memory grows per request, and the rate limiter can't fail open until it reconnects.
 * A client-options customizer adds to the options Boot builds, so {@code spring.data.redis.connect-timeout} still
 * applies; new {@code ClientOptions} would drop it.
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
