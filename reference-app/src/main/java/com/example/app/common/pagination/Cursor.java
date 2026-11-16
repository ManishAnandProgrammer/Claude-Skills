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

/**
 * The {@code after} parameter of a cursor-paged list: the sort it was taken in and the sort keys of
 * the last row the client saw. On the wire it is opaque to clients, Base64url JSON
 * {@code {"sort": …, "keys": […]}}. It carries only a position: the repository's own predicates
 * (ownership) still apply to every batch.
 *
 * <p>A key is taken only in the form {@link #write} gives it, and only inside what PostgreSQL stores:
 * anything else (unreadable, taken in another sort, a key missing or extra, of the wrong type, out of the
 * column's range) is a 400 {@code REQUEST_PARAMETER_INVALID} naming {@code after}, never a 500 at the
 * database. A new key type gets a reader in the same shape: parse, compare with what {@code write}
 * writes, check the column's range with fixed bounds.</p>
 */
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
     * 294276 AD, both start in 4713 BC. Years 1 to 9999 lie inside both and hold every value the API writes
     * (limits.yml's earliest and latest dates, the 9999-12-31 that sorts undated todos last). Compared as
     * values, never through a calendar: an Instant near its own extremes has no offset date.
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

    /** The cursor in {@code after}, taken in {@code expectedSort} with its {@code keyCount} keys; an absent one is the first batch. */
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

    /** A key of an enum column (a status order): one of the enum's names, exactly. */
    public <E extends Enum<E>> E enumValue(int index, Class<E> type) {
        try {
            return Enum.valueOf(type, keys.get(index));
        } catch (IllegalArgumentException unreadable) {
            throw invalid();
        }
    }

    /**
     * Text PostgreSQL can take: no U+0000 (well-formed JSON that a text column refuses, a 500 at the
     * database) and no lone surrogate, which UTF-8 can't carry (the driver would send a '?' in its place).
     */
    private static boolean isStorableText(String key) {
        return key.indexOf('\u0000') < 0 && new String(key.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).equals(key);
    }

    private static AppException invalid() {
        return AppException.withErrors(ErrorCode.REQUEST_PARAMETER_INVALID,
                List.of(RequestError.atParameter(PARAMETER, RequestError.INVALID_VALUE)));
    }
}
