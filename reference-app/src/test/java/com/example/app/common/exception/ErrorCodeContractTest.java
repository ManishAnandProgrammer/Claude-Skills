package com.example.app.common.exception;

import com.example.app.common.web.RetryAfterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** A new ErrorCode can't reach production without its HTTP status, its message, and a Retry-After when it is a 503. */
class ErrorCodeContractTest {

    private static final Properties MESSAGES = load("messages/message_en.properties");
    /** Every language's file, English's included: a project adds a language by adding its file. */
    private static final Map<String, Properties> LANGUAGES = loadEveryLanguage();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

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

    @Test
    @DisplayName("No message, error code's or bean validation's, in any language, has a single apostrophe: MessageFormat would drop it (write can''t)")
    void everyApostropheIsDoubled() {
        for (Map.Entry<String, Properties> language : LANGUAGES.entrySet()) {
            for (String key : language.getValue().stringPropertyNames()) {
                assertThat(language.getValue().getProperty(key)).as(language.getKey() + ": " + key).doesNotContainPattern("(?<!')'(?!')");
            }
        }
    }

    @Test
    @DisplayName("Every language's file has English's keys, each message with the same placeholders")
    void everyLanguageHasEveryKeyWithTheSamePlaceholders() {
        assertThat(LANGUAGES).containsKey("message_en.properties");
        for (Map.Entry<String, Properties> language : LANGUAGES.entrySet()) {
            Properties messages = language.getValue();
            assertThat(messages.stringPropertyNames()).as(language.getKey()).isEqualTo(MESSAGES.stringPropertyNames());
            for (String key : MESSAGES.stringPropertyNames()) {
                assertThat(placeholders(messages.getProperty(key))).as(language.getKey() + ": " + key)
                        .isEqualTo(placeholders(MESSAGES.getProperty(key)));
            }
        }
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
        return read(new ClassPathResource(resource));
    }

    private static Map<String, Properties> loadEveryLanguage() {
        Map<String, Properties> languages = new TreeMap<>();
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath*:messages/message_*.properties")) {
                languages.put(file.getFilename(), read(file));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot list the messages files", failure);
        }
        return languages;
    }

    private static Properties read(Resource file) {
        Properties properties = new Properties();
        try (InputStream stream = file.getInputStream()) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + file, failure);
        }
        return properties;
    }

    /** A message's placeholders, sorted: a translation may move them, never drop or rename one. */
    private static List<String> placeholders(String message) {
        return PLACEHOLDER.matcher(message).results().map(MatchResult::group).sorted().toList();
    }
}
