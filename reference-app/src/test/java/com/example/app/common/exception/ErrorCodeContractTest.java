package com.example.app.common.exception;

import com.example.app.common.web.RetryAfterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** A new ErrorCode can't reach production without its HTTP status, its message, and a Retry-After when it is a 503. */
class ErrorCodeContractTest {

    private static final Properties MESSAGES = load("messages/message_en.properties");

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("Every error code maps to an HTTP status")
    void everyErrorCodeHasAStatus(ErrorCode code) {
        assertThatCode(() -> ErrorCodeHttpStatusMapper.resolve(code)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("Every error code has an English message")
    void everyErrorCodeHasAMessage(ErrorCode code) {
        assertThat(MESSAGES.getProperty(code.name())).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("Every 503 code has a Retry-After range, so the client is told when to come back")
    void every503HasARetryAfterRange(ErrorCode code) {
        RetryAfterProperties.Range range = new RetryAfterProperties.Range(Duration.ofSeconds(1), Duration.ofSeconds(3));
        RetryAfterProperties retryAfter = new RetryAfterProperties(range, range, range,
                // media:begin
                range,
                // media:end
                range);

        assertThat(retryAfter.rangeFor(code).isPresent())
                .isEqualTo(ErrorCodeHttpStatusMapper.resolve(code) == HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream stream = ErrorCodeContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + resource, failure);
        }
        return properties;
    }
}
