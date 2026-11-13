package com.example.app.common.pagination;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.util.Locale;

/**
 * Case-insensitive "contains" on user text: {@code position(query in lower(column)) > 0}. Unlike
 * {@code LIKE}, the user's {@code %} and {@code _} need no escaping. It can't use a btree index;
 * where contains-search must be fast on a large table, add a {@code pg_trgm} index.
 */
public final class TextSearch {

    private TextSearch() {
    }

    public static Predicate contains(CriteriaBuilder builder, Expression<String> column, String query) {
        return builder.greaterThan(builder.locate(builder.lower(column), query.strip().toLowerCase(Locale.ROOT)), 0);
    }
}
