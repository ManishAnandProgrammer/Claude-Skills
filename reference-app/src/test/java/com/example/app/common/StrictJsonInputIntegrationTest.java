package com.example.app.common;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A request body's value is read only in its documented form: the settings are config/server.yml's
 * {@code spring.jackson.*}, and a failed read is the handler's 400 {@code REQUEST_UNREADABLE}. Checked on
 * the application's own mapper with one field per type, so it holds whatever requests the modules declare.
 */
class StrictJsonInputIntegrationTest extends IntegrationTest {

    private enum Level {
        LOW,
        HIGH
    }

    private record Scalars(LocalDate day, Instant moment, UUID id, Level level, Integer count, Long total, Boolean flag) {
    }

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("Each type's documented form is read")
    void theDocumentedFormsAreRead() {
        Scalars read = jsonMapper.readValue("""
                {"day": "2026-01-10", "moment": "2026-01-10T10:00:00Z", "id": "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d",
                 "level": "LOW", "count": 5, "total": 5, "flag": true}""", Scalars.class);

        assertThat(read).isEqualTo(new Scalars(LocalDate.of(2026, 1, 10), Instant.parse("2026-01-10T10:00:00Z"),
                UUID.fromString("0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d"), Level.LOW, 5, 5L, true));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', textBlock = """
            day    | "2026-01-10T10:00:00"
            day    | "2026-01-10T18:30:00Z"
            day    | "2026-1-10"
            day    | 20463
            moment | "2026-01-10"
            moment | "2026-01-10T10:00:00"
            id     | "0190a9c26c437cc19f2a1d1e5a6b7c8d"
            id     | 5
            level  | 0
            level  | "low"
            count  | "5"
            count  | 5.0
            count  | true
            total  | "5"
            total  | 5.0
            flag   | "true"
            flag   | 1
            """)
    @DisplayName("A value in another form is refused, never guessed at: a moment for a day, an enum's position, a quoted number")
    void anotherFormIsRefused(String field, String value) {
        String body = """
                {"%s": %s}""".formatted(field, value);

        assertThatExceptionOfType(JacksonException.class).isThrownBy(() -> jsonMapper.readValue(body, Scalars.class));
    }
}
