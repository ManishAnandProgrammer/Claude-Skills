package com.example.app.common.pagination;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.RequestError;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** It carries only a position: the repository's own predicates (ownership) still apply to every batch. */
public final class Cursor {

    private static final String PARAMETER = "after";
    private static final Cursor NONE = new Cursor(List.of());
    /** As strict as the API's own mapper: a cursor nobody wrote is refused, not guessed at. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    /*
     * java.time reads years up to ±999,999,999; PostgreSQL's date ends at 5874897 AD and its timestamp at
     * 294276 AD, both start in 4713 BC. Years 1 to 9999 lie inside both and hold every value the API writes.
     * Compared as values, never through a calendar: an Instant near its own extremes has no offset date.
     */
    private static final LocalDate EARLIEST_DAY = LocalDate.of(1, 1, 1);
    private static final LocalDate LATEST_DAY = LocalDate.of(9999, 12, 31);
    private static final Instant EARLIEST_MOMENT = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST_MOMENT = Instant.parse("9999-12-31T23:59:59.999999Z");
    /** PostgreSQL keeps microseconds: a finer key is a moment no row holds. */
    private static final int NANOS_PER_MICRO = 1_000;

    private final List<String> keys;

    private Cursor(List<String> keys) {
        this.keys = keys;
    }

    private record Content(String sort, List<String> keys) {
    }

    public static Cursor read(String after, String expectedSort, int keyCount) {
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
                || content.keys().size() != keyCount || content.keys().contains(null)
                || !content.keys().stream().allMatch(Cursor::isStorableText)) {
            throw invalid();
        }
        return new Cursor(List.copyOf(content.keys()));
    }

    public static String write(String sort, Object... keys) {
        List<String> texts = new ArrayList<>();
        for (Object key : keys) {
            texts.add(key.toString());
        }
        byte[] json = JSON.writeValueAsBytes(new Content(sort, texts));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    public boolean hasPosition() {
        return !keys.isEmpty();
    }

    public String text(int index) {
        return keys.get(index);
    }

    public UUID uuid(int index) {
        String key = keys.get(index);
        try {
            UUID id = UUID.fromString(key);
            // fromString also reads short forms ("1-1-1-1-1") that write never gives.
            if (!id.toString().equals(key)) {
                throw invalid();
            }
            return id;
        } catch (IllegalArgumentException unreadable) {
            throw invalid();
        }
    }

    public LocalDate date(int index) {
        String key = keys.get(index);
        try {
            LocalDate day = LocalDate.parse(key);
            if (day.isBefore(EARLIEST_DAY) || day.isAfter(LATEST_DAY)) {
                throw invalid();
            }
            // parse also reads a signed, padded year ("+02026-01-10") that write never gives.
            if (!day.toString().equals(key)) {
                throw invalid();
            }
            return day;
        } catch (DateTimeException unreadable) {
            throw invalid();
        }
    }

    public Instant instant(int index) {
        String key = keys.get(index);
        try {
            Instant moment = Instant.parse(key);
            if (moment.isBefore(EARLIEST_MOMENT) || moment.isAfter(LATEST_MOMENT) || moment.getNano() % NANOS_PER_MICRO != 0) {
                throw invalid();
            }
            // parse also reads an offset ("+01:00") and a leap second that write never gives.
            if (!moment.toString().equals(key)) {
                throw invalid();
            }
            return moment;
        } catch (DateTimeException unreadable) {
            throw invalid();
        }
    }

    /** An {@code integer} column's key: Java's int has PostgreSQL's integer range. */
    public Integer number(int index) {
        String key = keys.get(index);
        try {
            Integer number = Integer.valueOf(key);
            // valueOf also reads "+45" and "045".
            if (!number.toString().equals(key)) {
                throw invalid();
            }
            return number;
        } catch (NumberFormatException unreadable) {
            throw invalid();
        }
    }

    public <E extends Enum<E>> E enumValue(int index, Class<E> type) {
        try {
            return Enum.valueOf(type, keys.get(index));
        } catch (IllegalArgumentException unreadable) {
            throw invalid();
        }
    }

    /**
     * No U+0000 (well-formed JSON that a text column refuses, a 500 at the database) and no lone surrogate,
     * which UTF-8 can't carry (the driver would send a '?' in its place).
     */
    private static boolean isStorableText(String key) {
        return key.indexOf('\u0000') < 0 && new String(key.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).equals(key);
    }

    private static AppException invalid() {
        return AppException.withErrors(ErrorCode.REQUEST_PARAMETER_INVALID,
                List.of(RequestError.atParameter(PARAMETER, RequestError.INVALID_VALUE)));
    }
}
