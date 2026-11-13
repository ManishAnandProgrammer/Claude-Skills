package com.example.app.common.pagination;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;

import java.util.function.Function;

/**
 * One sort key of a keyset: where it is in the query, where it is in a row, and its type (to read
 * it back from a cursor). The key must be non-null in every row: a nullable column gets a stored
 * generated sort column instead.
 */
public record KeyColumn<T, R, V extends Comparable<? super V>>(Function<Root<T>, Expression<V>> path,
                                                                 Function<R, V> value,
                                                                 Class<V> type) {
}
