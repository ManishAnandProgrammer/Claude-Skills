package com.example.app.config;

import com.example.app.common.ratelimit.RateLimitProperties;
import com.example.app.support.IntegrationTest;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Redis client the rate limiter uses is bounded: commands refused while disconnected, a bounded
 * queue, Boot's connect timeout kept, and a command timeout just above the limiter's own (REL-04/09).
 */
class RedisClientIntegrationTest extends IntegrationTest {

    @Autowired
    private LettuceConnectionFactory connectionFactory;

    @Autowired
    private DataRedisProperties redisProperties;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RedisClientConfig.Settings settings;

    @Test
    @DisplayName("Commands are refused while disconnected, the queue is bounded, and the connect timeout still applies")
    void clientIsBounded() {
        ClientOptions options = connectionFactory.getClientConfiguration().getClientOptions().orElseThrow();

        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(options.getRequestQueueSize()).isEqualTo(settings.requestQueueSize());
        assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(redisProperties.getConnectTimeout());
    }

    @Test
    @DisplayName("The command timeout is set, and longer than the rate limiter's backend timeout, which fires first")
    void commandTimeoutNestsAboveTheLimiterTimeout() {
        LettuceClientConfiguration client = connectionFactory.getClientConfiguration();

        assertThat(client.getCommandTimeout()).isEqualTo(redisProperties.getTimeout());
        assertThat(client.getCommandTimeout()).isGreaterThan(rateLimitProperties.backendTimeout());
    }
}
