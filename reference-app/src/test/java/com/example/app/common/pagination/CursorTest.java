package com.example.app.common.pagination;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
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
                    assertThat(refused.getExtensions()).containsEntry("parameter", "after");
                });
    }

    @Test
    @DisplayName("No after is the first batch")
    void absentIsFirstBatch() {
        assertThat(Cursor.read(null, "AMOUNT").isPresent()).isFalse();
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

        Cursor cursor = Cursor.read(Cursor.write("ALL", "Groceries", ID, day, moment, 45), "ALL");

        assertThat(cursor.isPresent()).isTrue();
        assertThat(cursor.text(0)).isEqualTo("Groceries");
        assertThat(cursor.uuid(1)).isEqualTo(ID);
        assertThat(cursor.date(2)).isEqualTo(day);
        assertThat(cursor.instant(3)).isEqualTo(moment);
        assertThat(cursor.number(4)).isEqualTo(45);
    }

    @Test
    @DisplayName("A cursor written by hand in the documented format is read")
    void handWrittenCursorIsRead() {
        String after = encode("""
                {"sort": "AMOUNT", "keys": ["45", "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d"]}
                """);

        Cursor cursor = Cursor.read(after, "AMOUNT");

        assertThat(cursor.number(0)).isEqualTo(45);
        assertThat(cursor.uuid(1)).isEqualTo(ID);
    }

    @Test
    @DisplayName("A tampered cursor (not Base64url, not JSON, an unknown field) is refused")
    void tamperedCursorIsRefused() {
        assertRefused(() -> Cursor.read("not base64!", "AMOUNT"));
        assertRefused(() -> Cursor.read(encode("not json"), "AMOUNT"));
        assertRefused(() -> Cursor.read(encode("""
                {"sort": "AMOUNT", "keys": ["45"], "extra": 1}
                """), "AMOUNT"));
    }

    @Test
    @DisplayName("A cursor from another sort is refused")
    void anotherSortIsRefused() {
        assertRefused(() -> Cursor.read(Cursor.write("NAME_ASC", "Groceries", ID), "NAME_DESC"));
    }

    @Test
    @DisplayName("Missing keys are refused: none, null, empty, or fewer than the sort reads")
    void missingKeysAreRefused() {
        assertRefused(() -> Cursor.read(encode("""
                {"sort": "AMOUNT"}
                """), "AMOUNT"));
        assertRefused(() -> Cursor.read(encode("""
                {"sort": "AMOUNT", "keys": []}
                """), "AMOUNT"));
        assertRefused(() -> Cursor.read(encode("""
                {"sort": "AMOUNT", "keys": ["45", null]}
                """), "AMOUNT"));
        Cursor oneKey = Cursor.read(Cursor.write("AMOUNT", 45), "AMOUNT");
        assertRefused(() -> oneKey.uuid(1));
    }

    @Test
    @DisplayName("A key of the wrong type is 400, not a 500: a bad number, id, date or instant")
    void badKeyIsRefused() {
        Cursor cursor = Cursor.read(Cursor.write("AMOUNT", "forty-five"), "AMOUNT");

        assertRefused(() -> cursor.number(0));
        assertRefused(() -> cursor.uuid(0));
        assertRefused(() -> cursor.date(0));
        assertRefused(() -> cursor.instant(0));
    }
}
