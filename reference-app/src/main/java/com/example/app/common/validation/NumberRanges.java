package com.example.app.common.validation;

import java.util.Objects;

/**
 * A missing bound counts as in order: its own {@code @NotNull} reports it. A filter asks it here rather than in
 * its own method: static analysis reads a field compared there as never null-checked.
 */
public final class NumberRanges {

    private NumberRanges() {
    }

    public static <T extends Comparable<T>> boolean isOrdered(T min, T max) {
        return Objects.isNull(min) || Objects.isNull(max) || min.compareTo(max) <= 0;
    }
}
