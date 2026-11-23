package com.example.app.common.validation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** A missing day counts as in order: its own {@code @NotNull} reports it. */
public final class DateRanges {

    private DateRanges() {
    }

    public static boolean isOrdered(LocalDate first, LocalDate last) {
        return Objects.isNull(first) || Objects.isNull(last) || !last.isBefore(first);
    }

    public static Long lengthInDays(LocalDate first, LocalDate last) {
        if (Objects.isNull(first) || Objects.isNull(last) || last.isBefore(first)) {
            return null;
        }
        return ChronoUnit.DAYS.between(first, last) + 1;
    }
}
