package com.example.app.common.pagination;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.RequestError;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CursorTest {

    private static final UUID ID = UUID.fromString("0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d");

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.strip().getBytes(StandardCharsets.UTF_8));
    }

    private static void assertRefused(ThrowingCallable reading) {
        assertThatExceptionOfType(AppException.class)
                .isThrownBy(reading)
                .satisfies(refused -> {
                    assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.REQUEST_PARAMETER_INVALID);
                    assertThat(refused.getErrors()).containsExactly(RequestError.atParameter("after", "invalid value"));
                });
    }

    @Test
    @DisplayName("A key holding U+0000 is refused like a tampered cursor: PostgreSQL can't take it as text")
    void aKeyWithANulCharacterIsRefused() {
        String cursor = Cursor.write("NAME", "Lists\u0000", ID);

        assertRefused(() -> Cursor.read(cursor, "NAME", 2));
    }

    @Test
    @DisplayName("No after is the first batch")
    void absentIsFirstBatch() {
        assertThat(Cursor.read(null, "AMOUNT", 2).hasPosition()).isFalse();
    }

    @Test
    @DisplayName("The wire format is Base64url JSON {sort, keys} without padding, so existing cursors keep working")
    void wireFormatIsUnchanged() {
        String written = Cursor.write("AMOUNT", 45, ID);

        assertThat(written).doesNotContain("=");
        assertThat(new String(Base64.getUrlDecoder().decode(written), StandardCharsets.UTF_8))
                .isEqualTo("""
                        {"sort":"AMOUNT","keys":["45","0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d"]}""");
    }

    @Test
    @DisplayName("Every key type is read back as written")
    void keysAreReadBack() {
        LocalDate day = LocalDate.of(2026, 11, 14);
        Instant moment = Instant.parse("2026-11-14T10:15:30Z");

        Instant microseconds = Instant.parse("2026-11-14T10:15:30.123456Z");

        Cursor cursor = Cursor.read(Cursor.write("ALL", "Groceries", ID, day, moment, 45, Priority.HIGH, microseconds, -7), "ALL", 8);

        assertThat(cursor.hasPosition()).isTrue();
        assertThat(cursor.text(0)).isEqualTo("Groceries");
        assertThat(cursor.uuid(1)).isEqualTo(ID);
        assertThat(cursor.date(2)).isEqualTo(day);
        assertThat(cursor.instant(3)).isEqualTo(moment);
        assertThat(cursor.number(4)).isEqualTo(45);
        assertThat(cursor.enumValue(5, Priority.class)).isEqualTo(Priority.HIGH);
        assertThat(cursor.instant(6)).isEqualTo(microseconds);
        assertThat(cursor.number(7)).isEqualTo(-7);
    }

    @Test
    @DisplayName("A cursor written by hand in the documented format is read")
    void handWrittenCursorIsRead() {
        String after = encode("""
                {"sort": "AMOUNT", "keys": ["45", "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d"]}
                """);

        Cursor cursor = Cursor.read(after, "AMOUNT", 2);

        assertThat(cursor.number(0)).isEqualTo(45);
        assertThat(cursor.uuid(1)).isEqualTo(ID);
    }

    @Test
    @DisplayName("A tampered cursor (not Base64url, not JSON, an unknown field) is refused")
    void tamperedCursorIsRefused() {
        assertRefused(() -> Cursor.read("not base64!", "AMOUNT", 1));
        assertRefused(() -> Cursor.read(encode("not json"), "AMOUNT", 1));
        assertRefused(() -> Cursor.read(encode("""
                {"sort": "AMOUNT", "keys": ["45"], "extra": 1}
                """), "AMOUNT", 1));
    }

    @Test
    @DisplayName("A cursor from another sort is refused")
    void anotherSortIsRefused() {
        assertRefused(() -> Cursor.read(Cursor.write("NAME_ASC", "Groceries", ID), "NAME_DESC", 2));
        assertRefused(() -> Cursor.read(encode("""
                {"keys": ["Groceries", "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d"]}
                """), "NAME_ASC", 2));
    }

    @Test
    @DisplayName("A key count other than the sort's is refused: none, null, empty, fewer or more keys")
    void otherKeyCountsAreRefused() {
        assertRefused(() -> Cursor.read(encode("""
                {"sort": "AMOUNT"}
                """), "AMOUNT", 2));
        assertRefused(() -> Cursor.read(encode("""
                {"sort": "AMOUNT", "keys": []}
                """), "AMOUNT", 2));
        assertRefused(() -> Cursor.read(encode("""
                {"sort": "AMOUNT", "keys": ["45", null]}
                """), "AMOUNT", 2));
        assertRefused(() -> Cursor.read(Cursor.write("AMOUNT", 45), "AMOUNT", 2));
        assertRefused(() -> Cursor.read(Cursor.write("AMOUNT", 45, ID, ID), "AMOUNT", 2));
    }

    @Test
    @DisplayName("A key of the wrong type is 400, not a 500: a bad number, id, date or instant")
    void badKeyIsRefused() {
        Cursor cursor = Cursor.read(Cursor.write("AMOUNT", "forty-five"), "AMOUNT", 1);

        assertRefused(() -> cursor.number(0));
        assertRefused(() -> cursor.uuid(0));
        assertRefused(() -> cursor.date(0));
        assertRefused(() -> cursor.instant(0));
        assertRefused(() -> cursor.enumValue(0, Priority.class));
    }

    @Test
    @DisplayName("A day outside years 1-9999 is refused, java.time's extremes included: PostgreSQL can't store it, a 500 at the database")
    void aDayOutsideTheStorableYearsIsRefused() {
        for (String day : List.of("-300000-01-01", "+300000-01-01", "0000-12-31", "+10000-01-01",
                LocalDate.MIN.toString(), LocalDate.MAX.toString())) {
            assertRefused(() -> keyOf(day).date(0));
        }
        assertThat(keyOf("0001-01-01").date(0)).isEqualTo(LocalDate.of(1, 1, 1));
        assertThat(keyOf("9999-12-31").date(0)).isEqualTo(LocalDate.of(9999, 12, 31));
    }

    @Test
    @DisplayName("A moment outside years 1-9999, Instant's extremes included, or finer than a microsecond, is refused")
    void aMomentOutsideTheStorableYearsIsRefused() {
        for (String moment : List.of("-290000-01-01T00:00:00Z", "+290000-01-01T00:00:00Z", "0000-12-31T23:59:59.999999Z",
                "+10000-01-01T00:00:00Z", Instant.MIN.toString(), Instant.MAX.toString(), "2026-11-14T10:15:30.123456789Z")) {
            assertRefused(() -> keyOf(moment).instant(0));
        }
        assertThat(keyOf("0001-01-01T00:00:00Z").instant(0)).isEqualTo(Instant.parse("0001-01-01T00:00:00Z"));
        assertThat(keyOf("9999-12-31T23:59:59.999999Z").instant(0)).isEqualTo(Instant.parse("9999-12-31T23:59:59.999999Z"));
    }

    @Test
    @DisplayName("""
            A key Java reads but write never gives is refused: a short or upper-case id, a signed or padded number, \
            a day with a signed, padded year, a moment with an offset""")
    void aKeyNotAsWrittenIsRefused() {
        assertRefused(() -> keyOf("1-1-1-1-1").uuid(0));
        assertRefused(() -> keyOf(ID.toString().toUpperCase(Locale.ROOT)).uuid(0));
        for (String number : List.of("+45", "045", "-0", "2147483648", " 45")) {
            assertRefused(() -> keyOf(number).number(0));
        }
        assertRefused(() -> keyOf("+02026-01-10").date(0));
        assertRefused(() -> keyOf("2026-11-14T11:15:30+01:00").instant(0));
        assertRefused(() -> keyOf("high").enumValue(0, Priority.class));
        assertThat(keyOf("2147483647").number(0)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("A text key with a lone surrogate is refused: UTF-8 can't carry it, the driver would send '?'")
    void aKeyWithALoneSurrogateIsRefused() {
        assertRefused(() -> Cursor.read(encode("""
                {"sort": "NAME", "keys": ["Lists\\ud800", "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d"]}
                """), "NAME", 2));
        assertThat(Cursor.read(Cursor.write("NAME", "Lists 𐌰", ID), "NAME", 2).text(0)).isEqualTo("Lists 𐌰");
    }

    private static Cursor keyOf(String key) {
        return Cursor.read(Cursor.write("ONE", key), "ONE", 1);
    }

    private enum Priority { LOW, HIGH }
}
