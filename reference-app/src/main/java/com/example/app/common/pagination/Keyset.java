package com.example.app.common.pagination;

import java.util.List;

/**
 * A sort order a list can be scrolled in: its name (carried in the cursor, so a cursor from one
 * order is refused under another), its direction, and its key columns, the last of which must be
 * unique (the id) so every row has exactly one position.
 */
public record Keyset<T, R>(String name, boolean descending, List<KeyColumn<T, R, ?>> columns) {

    public Keyset {
        columns = List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("a keyset needs at least its unique tie-breaker column");
        }
    }
}
