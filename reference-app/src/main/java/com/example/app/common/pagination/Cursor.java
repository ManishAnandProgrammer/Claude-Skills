package com.example.app.common.pagination;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.RequestError;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code after} parameter of a cursor-paged list: the sort it was taken in and the sort keys of
 * the last row the client saw. On the wire it is opaque to clients, Base64url JSON
 * {@code {"sort": …, "keys": […]}}. It carries only a position: the repository's own predicates
 * (ownership) still apply to every batch.
 *
 * <p>Anything wrong with it (unreadable, taken in another sort, a key missing or of the wrong type)
 * is a 400 {@code REQUEST_PARAMETER_INVALID} naming {@code after}, never a 500.</p>
 */
public final class Cursor {

    private static final String PARAMETER = "after";
    private static final Cursor NONE = new Cursor(List.of());
    /** As strict as the API's own mapper: a cursor nobody wrote is refused, not guessed at. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final List<String> keys;

    private Cursor(List<String> keys) {
        this.keys = keys;
    }

    private record Content(String sort, List<String> keys) {
    }

    /** The cursor in {@code after}; an absent one is the first batch. */
    public static Cursor read(String after, String expectedSort) {
        if (Objects.isNull(after)) {
            return NONE;
        }
        Content content;
        try {
            content = JSON.readValue(Base64.getUrlDecoder().decode(after), Content.class);
        } catch (IllegalArgumentException | JacksonException unreadable) {
            throw invalid();
        }
        if (Objects.isNull(content) || !expectedSort.equals(content.sort()) || Objects.isNull(content.keys())
                || content.keys().isEmpty() || content.keys().contains(null)) {
            throw invalid();
        }
        return new Cursor(List.copyOf(content.keys()));
    }

    /** The cursor of a row: its sort keys in the order the list is sorted by. */
    public static String write(String sort, Object... keys) {
        List<String> texts = new ArrayList<>();
        for (Object key : keys) {
            texts.add(key.toString());
        }
        byte[] json = JSON.writeValueAsBytes(new Content(sort, texts));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    /** {@code false} for the first batch. */
    public boolean isPresent() {
        return !keys.isEmpty();
    }

    public String text(int index) {
        return key(index);
    }

    public UUID uuid(int index) {
        try {
            return UUID.fromString(key(index));
        } catch (IllegalArgumentException unreadable) {
            throw invalid();
        }
    }

    public LocalDate date(int index) {
        try {
            return LocalDate.parse(key(index));
        } catch (DateTimeParseException unreadable) {
            throw invalid();
        }
    }

    public Instant instant(int index) {
        try {
            return Instant.parse(key(index));
        } catch (DateTimeParseException unreadable) {
            throw invalid();
        }
    }

    public Integer number(int index) {
        try {
            return Integer.valueOf(key(index));
        } catch (NumberFormatException unreadable) {
            throw invalid();
        }
    }

    private String key(int index) {
        if (index >= keys.size()) {
            throw invalid();
        }
        return keys.get(index);
    }

    private static AppException invalid() {
        return AppException.withErrors(ErrorCode.REQUEST_PARAMETER_INVALID,
                List.of(RequestError.atParameter(PARAMETER, RequestError.INVALID_VALUE)));
    }
}
