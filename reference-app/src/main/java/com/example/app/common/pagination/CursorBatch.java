package com.example.app.common.pagination;

import java.util.List;
import java.util.function.Function;

public record CursorBatch<R>(List<R> items, String nextCursor) {

    public CursorBatch {
        items = List.copyOf(items);
    }

    /** {@code rows} read with {@code setMaxResults(size + 1)}: the extra row only tells that a next batch exists. */
    public static <R> CursorBatch<R> of(List<R> rows, int size, Function<R, String> cursorOfLastRow) {
        if (rows.size() <= size) {
            return new CursorBatch<>(rows, null);
        }
        List<R> batch = rows.subList(0, size);
        return new CursorBatch<>(batch, cursorOfLastRow.apply(batch.getLast()));
    }
}
