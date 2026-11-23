package com.example.app.common;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

    private static final String PROBES = "/api/v1/hostile-input-probes";
    private static final String VALID_ID = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d";
    private static final String MISSING = "<missing>";
    private static final String NUL = "<NUL>";
    private static final String SURROGATE = "<SURROGATE>";

    private static Map<String, String> validBody() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("text", "\"probe\"");
        fields.put("day", "\"2026-01-10\"");
        fields.put("moment", "\"2026-01-10T10:00:00Z\"");
        fields.put("id", "\"" + VALID_ID + "\"");
        fields.put("level", "\"LOW\"");
        fields.put("count", "5");
        fields.put("flag", "true");
        fields.put("ids", "[\"" + VALID_ID + "\"]");
        return fields;
    }

    private static Map<String, String> validQuery() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("text", "probe");
        parameters.put("day", "2026-01-10");
        parameters.put("moment", "2026-01-10T10:00:00Z");
        parameters.put("id", VALID_ID);
        parameters.put("level", "LOW");
        parameters.put("count", "5");
        parameters.put("flag", "true");
        parameters.put("ids", VALID_ID);
        return parameters;
    }

    private static List<String> validCursorKeys() {
        return new ArrayList<>(List.of("\"probe\"", "\"2026-01-10\"", "\"2026-01-10T10:00:00Z\"", "\"" + VALID_ID + "\"", "\"5\""));
    }

    private static final List<String> CURSOR_KEY_ORDER = List.of("text", "day", "moment", "id", "count");

    @Test
    @DisplayName("The probe takes every value in its documented form, through every channel")
    void theProbeTakesTheDocumentedForms() throws Exception {
        String token = newUser().accessToken();

        assertThat(sendBody(token, validBody()).getStatus()).isEqualTo(200);
        assertThat(sendQuery(token, validQuery()).getStatus()).isEqualTo(200);
        assertThat(sendCursor(token, validCursorKeys()).getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            body   | out of range   | "<101 letters>"   | VALIDATION_FAILED
            body   | empty          | ""                | VALIDATION_FAILED
            body   | NUL            | "a<NUL>b"         | VALIDATION_FAILED
            body   | lone surrogate | "a<SURROGATE>b"   | VALIDATION_FAILED
            body   | missing        | <missing>         | VALIDATION_FAILED
            body   | extra          | ["probe"]         | REQUEST_UNREADABLE
            query  | out of range   | <101 letters>     | VALIDATION_FAILED
            query  | empty          |                   | VALIDATION_FAILED
            query  | NUL            | a<NUL>b           | VALIDATION_FAILED
            query  | lone surrogate | a<SURROGATE>b     | VALIDATION_FAILED
            query  | missing        | <missing>         | VALIDATION_FAILED
            cursor | NUL            | "a<NUL>b"         | REQUEST_PARAMETER_INVALID
            cursor | lone surrogate | "a<SURROGATE>b"   | REQUEST_PARAMETER_INVALID
            cursor | missing        | <missing>         | REQUEST_PARAMETER_INVALID
            cursor | extra          | ["probe"]         | REQUEST_PARAMETER_INVALID
            """)
    @DisplayName("Text: each hostile form is 400 with its code")
    void hostileText(String channel, String form, String value, String errorCode) throws Exception {
        assertRefused(channel, "text", Objects.requireNonNullElse(value, "").replace("<101 letters>", "a".repeat(101)), errorCode);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            body   | wrong type     | 20463                  | REQUEST_UNREADABLE
            body   | out of range   | "3000-01-01"           | VALIDATION_FAILED
            body   | non-canonical  | "2026-1-10"            | REQUEST_UNREADABLE
            body   | empty          | ""                     | REQUEST_UNREADABLE
            body   | NUL            | "2026-01-<NUL>10"      | REQUEST_UNREADABLE
            body   | lone surrogate | "2026-01-<SURROGATE>10" | REQUEST_UNREADABLE
            body   | missing        | <missing>              | VALIDATION_FAILED
            query  | wrong type     | tomorrow               | REQUEST_PARAMETER_INVALID
            query  | out of range   | 3000-01-01             | VALIDATION_FAILED
            query  | non-canonical  | 2026-1-10              | REQUEST_PARAMETER_INVALID
            query  | empty          |                        | VALIDATION_FAILED
            query  | NUL            | 2026-01-<NUL>10        | REQUEST_PARAMETER_INVALID
            query  | lone surrogate | 2026-01-<SURROGATE>10  | REQUEST_PARAMETER_INVALID
            query  | missing        | <missing>              | VALIDATION_FAILED
            cursor | wrong type     | "tomorrow"             | REQUEST_PARAMETER_INVALID
            cursor | out of range   | "10000-01-01"          | REQUEST_PARAMETER_INVALID
            cursor | non-canonical  | "+02026-01-10"         | REQUEST_PARAMETER_INVALID
            cursor | empty          | ""                     | REQUEST_PARAMETER_INVALID
            cursor | NUL            | "2026-01-<NUL>10"      | REQUEST_PARAMETER_INVALID
            """)
    @DisplayName("LocalDate: each hostile form is 400 with its code")
    void hostileDate(String channel, String form, String value, String errorCode) throws Exception {
        assertRefused(channel, "day", value, errorCode);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            body   | non-canonical  | "2026-01-10"                     | REQUEST_UNREADABLE
            body   | empty          | ""                               | REQUEST_UNREADABLE
            body   | NUL            | "2026-01-10T10:<NUL>00:00Z"      | REQUEST_UNREADABLE
            body   | lone surrogate | "2026-01-10T10:<SURROGATE>00:00Z" | REQUEST_UNREADABLE
            body   | missing        | <missing>                        | VALIDATION_FAILED
            query  | wrong type     | soon                             | REQUEST_PARAMETER_INVALID
            query  | non-canonical  | 2026-01-10                       | REQUEST_PARAMETER_INVALID
            query  | empty          |                                  | VALIDATION_FAILED
            query  | NUL            | 2026-01-10T10:<NUL>00:00Z        | REQUEST_PARAMETER_INVALID
            query  | missing        | <missing>                        | VALIDATION_FAILED
            cursor | wrong type     | "soon"                           | REQUEST_PARAMETER_INVALID
            cursor | out of range   | "+10000-01-01T00:00:00Z"         | REQUEST_PARAMETER_INVALID
            cursor | non-canonical  | "2026-01-10T11:00:00+01:00"      | REQUEST_PARAMETER_INVALID
            cursor | empty          | ""                               | REQUEST_PARAMETER_INVALID
            """)
    @DisplayName("Instant: each hostile form is 400 with its code")
    void hostileInstant(String channel, String form, String value, String errorCode) throws Exception {
        assertRefused(channel, "moment", value, errorCode);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            body   | wrong type     | 5                                         | REQUEST_UNREADABLE
            body   | non-canonical  | "0190a9c26c437cc19f2a1d1e5a6b7c8d"        | REQUEST_UNREADABLE
            body   | empty          | ""                                        | VALIDATION_FAILED
            body   | NUL            | "0190a9c2-<NUL>6c43-7cc1-9f2a-1d1e5a6b7c8d" | REQUEST_UNREADABLE
            body   | missing        | <missing>                                 | VALIDATION_FAILED
            body   | extra          | ["0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d"]  | REQUEST_UNREADABLE
            query  | wrong type     | 5                                         | REQUEST_PARAMETER_INVALID
            query  | non-canonical  | 0190a9c26c437cc19f2a1d1e5a6b7c8d          | REQUEST_PARAMETER_INVALID
            query  | empty          |                                           | VALIDATION_FAILED
            query  | NUL            | 0190a9c2-<NUL>6c43-7cc1-9f2a-1d1e5a6b7c8d | REQUEST_PARAMETER_INVALID
            query  | missing        | <missing>                                 | VALIDATION_FAILED
            cursor | wrong type     | "5"                                       | REQUEST_PARAMETER_INVALID
            cursor | non-canonical  | "0190A9C2-6C43-7CC1-9F2A-1D1E5A6B7C8D"    | REQUEST_PARAMETER_INVALID
            cursor | empty          | ""                                        | REQUEST_PARAMETER_INVALID
            """)
    @DisplayName("UUID: each hostile form is 400 with its code")
    void hostileUuid(String channel, String form, String value, String errorCode) throws Exception {
        assertRefused(channel, "id", value, errorCode);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            body   | wrong type     | 0             | REQUEST_UNREADABLE
            body   | out of range   | "MEDIUM"      | REQUEST_UNREADABLE
            body   | non-canonical  | "low"         | REQUEST_UNREADABLE
            body   | empty          | ""            | REQUEST_UNREADABLE
            body   | NUL            | "L<NUL>OW"    | REQUEST_UNREADABLE
            body   | missing        | <missing>     | VALIDATION_FAILED
            body   | extra          | ["LOW"]       | REQUEST_UNREADABLE
            query  | wrong type     | 0             | REQUEST_PARAMETER_INVALID
            query  | out of range   | MEDIUM        | REQUEST_PARAMETER_INVALID
            query  | non-canonical  | low           | REQUEST_PARAMETER_INVALID
            query  | empty          |               | VALIDATION_FAILED
            query  | NUL            | L<NUL>OW      | REQUEST_PARAMETER_INVALID
            query  | missing        | <missing>     | VALIDATION_FAILED
            """)
    @DisplayName("Enum: each hostile form is 400 with its code")
    void hostileEnum(String channel, String form, String value, String errorCode) throws Exception {
        assertRefused(channel, "level", value, errorCode);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            body   | wrong type     | "5"        | REQUEST_UNREADABLE
            body   | out of range   | 101        | VALIDATION_FAILED
            body   | out of range   | -1         | VALIDATION_FAILED
            body   | out of range   | 2147483648 | REQUEST_UNREADABLE
            body   | non-canonical  | 5.0        | REQUEST_UNREADABLE
            body   | empty          | ""         | REQUEST_UNREADABLE
            body   | missing        | <missing>  | VALIDATION_FAILED
            body   | extra          | [5]        | REQUEST_UNREADABLE
            query  | wrong type     | five       | REQUEST_PARAMETER_INVALID
            query  | out of range   | 101        | VALIDATION_FAILED
            query  | out of range   | -1         | VALIDATION_FAILED
            query  | out of range   | 2147483648 | REQUEST_PARAMETER_INVALID
            query  | non-canonical  | 5.0        | REQUEST_PARAMETER_INVALID
            query  | empty          |            | VALIDATION_FAILED
            query  | NUL            | 1<NUL>5     | REQUEST_PARAMETER_INVALID
            query  | missing        | <missing>  | VALIDATION_FAILED
            cursor | wrong type     | "five"     | REQUEST_PARAMETER_INVALID
            cursor | out of range   | "2147483648" | REQUEST_PARAMETER_INVALID
            cursor | non-canonical  | "05"       | REQUEST_PARAMETER_INVALID
            cursor | empty          | ""         | REQUEST_PARAMETER_INVALID
            """)
    @DisplayName("Integer: each hostile form is 400 with its code")
    void hostileInteger(String channel, String form, String value, String errorCode) throws Exception {
        assertRefused(channel, "count", value, errorCode);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            body   | wrong type     | 1          | REQUEST_UNREADABLE
            body   | non-canonical  | "true"     | REQUEST_UNREADABLE
            body   | empty          | ""         | REQUEST_UNREADABLE
            body   | missing        | <missing>  | VALIDATION_FAILED
            body   | extra          | [true]     | REQUEST_UNREADABLE
            query  | wrong type     | maybe      | REQUEST_PARAMETER_INVALID
            query  | empty          |            | VALIDATION_FAILED
            query  | NUL            | tr<NUL>ue  | REQUEST_PARAMETER_INVALID
            query  | missing        | <missing>  | VALIDATION_FAILED
            """)
    @DisplayName("Boolean: each hostile form is 400 with its code")
    void hostileBoolean(String channel, String form, String value, String errorCode) throws Exception {
        assertRefused(channel, "flag", value, errorCode);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            body   | wrong type     | "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d"      | REQUEST_UNREADABLE
            body   | out of range   | <101 ids>                                   | VALIDATION_FAILED
            body   | non-canonical  | ["0190a9c26c437cc19f2a1d1e5a6b7c8d"]        | REQUEST_UNREADABLE
            body   | NUL            | ["0190a9c2-<NUL>6c43-7cc1-9f2a-1d1e5a6b7c8d"] | REQUEST_UNREADABLE
            body   | missing        | <missing>                                   | VALIDATION_FAILED
            body   | extra          | [null]                                      | VALIDATION_FAILED
            query  | wrong type     | 5                                           | REQUEST_PARAMETER_INVALID
            query  | out of range   | <101 ids>                                   | VALIDATION_FAILED
            query  | non-canonical  | 0190a9c26c437cc19f2a1d1e5a6b7c8d            | REQUEST_PARAMETER_INVALID
            query  | missing        | <missing>                                   | VALIDATION_FAILED
            """)
    @DisplayName("Collection: each hostile form is 400 with its code")
    void hostileCollection(String channel, String form, String value, String errorCode) throws Exception {
        assertRefused(channel, "ids", Objects.requireNonNullElse(value, "").replace("<101 ids>", channel.equals("body") ? manyIdsJson(101) : randomIds(101)), errorCode);
    }

    private static String manyIdsJson(int count) {
        List<String> ids = new ArrayList<>();
        for (int id = 0; id < count; id++) {
            ids.add("\"" + UUID.randomUUID() + "\"");
        }
        return "[" + String.join(",", ids) + "]";
    }

    private void assertRefused(String channel, String field, String value, String errorCode) throws Exception {
        String token = newUser().accessToken();
        String raw = Objects.requireNonNullElse(value, "");
        MockHttpServletResponse answer = switch (channel) {
            case "body" -> {
                Map<String, String> body = validBody();
                if (raw.equals(MISSING)) {
                    body.remove(field);
                } else {
                    body.put(field, raw.replace(NUL, "\\u0000").replace(SURROGATE, "\\uD800"));
                }
                yield sendBody(token, body);
            }
            case "query" -> {
                Map<String, String> query = validQuery();
                if (raw.equals(MISSING)) {
                    query.remove(field);
                } else {
                    query.put(field, raw.replace(NUL, "\u0000").replace(SURROGATE, "\uD800"));
                }
                yield sendQuery(token, query);
            }
            case "cursor" -> {
                List<String> keys = validCursorKeys();
                int index = CURSOR_KEY_ORDER.indexOf(field);
                if (raw.equals(MISSING)) {
                    keys.remove(index);
                } else if (raw.startsWith("[")) {
                    keys.add(keys.get(index));
                } else {
                    keys.set(index, raw.replace(NUL, "\\u0000").replace(SURROGATE, "\\uD800"));
                }
                yield sendCursor(token, keys);
            }
            default -> throw new IllegalArgumentException("no channel " + channel);
        };

        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(400);
        assertThat(jsonMapper.readTree(answer.getContentAsString()).path("errorCode").asString()).isEqualTo(errorCode);
    }

    private MockHttpServletResponse sendBody(String token, Map<String, String> fields) throws Exception {
        List<String> members = new ArrayList<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            members.add("\"" + field.getKey() + "\": " + field.getValue());
        }
        return mvc.perform(post(PROBES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{" + String.join(", ", members) + "}")).andReturn().getResponse();
    }

    private MockHttpServletResponse sendQuery(String token, Map<String, String> parameters) throws Exception {
        MockHttpServletRequestBuilder request = get(PROBES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.param(parameter.getKey(), parameter.getValue());
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse sendCursor(String token, List<String> keys) throws Exception {
        String json = "{\"sort\": \"PROBE\", \"keys\": [" + String.join(", ", keys) + "]}";
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return mvc.perform(get(PROBES + "/cursor").param("cursor", cursor).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse();
    }
}

