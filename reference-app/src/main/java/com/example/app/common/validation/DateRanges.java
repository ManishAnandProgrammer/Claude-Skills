package com.example.app.common.validation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A day range's two questions, both ends inclusive, in one place: every request and filter with a range
 * asks them (a create, an update that moves the range, the list's range filter), and copies of the
 * condition drift. A missing day is not this class's to report: its own {@code @NotNull} does.
 */
public final class DateRanges {

    private DateRanges() {
    }

    /** True unless both days are given and the last is before the first (a one-day range is ordered). */
    public static boolean isOrdered(LocalDate first, LocalDate last) {
        return Objects.isNull(first) || Objects.isNull(last) || !last.isBefore(first);
    }

    /** The days from first to last, both counted; null when a day is missing or the range inverted. */
    public static Long lengthInDays(LocalDate first, LocalDate last) {
        if (Objects.isNull(first) || Objects.isNull(last) || last.isBefore(first)) {
            return null;
        }
        return ChronoUnit.DAYS.between(first, last) + 1;
    }
}
