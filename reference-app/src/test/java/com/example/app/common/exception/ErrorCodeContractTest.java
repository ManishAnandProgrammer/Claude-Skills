package com.example.app.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** A new ErrorCode can't reach production without its HTTP status and its message. */
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
