package com.example.app.common.pagination;

import java.util.List;

/**
 * One batch of a cursor-paged list as {@link KeysetPages} reads it: the rows, and the cursor of the
 * next batch ({@code null} on the last). The service wraps it for the API.
 */
public record CursorBatch<R>(List<R> items, String nextCursor) {

    public CursorBatch {
        items = List.copyOf(items);
    }
}
