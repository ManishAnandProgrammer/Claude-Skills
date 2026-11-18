package com.example.app.common.validation;

import java.util.Objects;

/**
 * A number range's question in one place, as {@link DateRanges} asks a day range's: every request and filter with a
 * minimum and a maximum asks it, and copies of the condition drift. A filter (a bean Spring binds through its setters)
 * asks it here rather than in its own method: static analysis reads a field compared there as never null-checked. A
 * missing bound is not this class's to report: its own {@code @NotNull} does.
 */
public final class NumberRanges {

    private NumberRanges() {
    }

    /** True unless both bounds are given and the maximum is below the minimum (equal bounds are ordered). */
    public static <T extends Comparable<T>> boolean isOrdered(T min, T max) {
        return Objects.isNull(min) || Objects.isNull(max) || min.compareTo(max) <= 0;
    }
}
