package com.example.app.common.pagination;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.util.Locale;

/**
 * Case-insensitive "contains" on user text: {@code position(query in lower(column)) > 0}. Unlike
 * {@code LIKE}, the user's {@code %} and {@code _} need no escaping. No index serves it, a {@code pg_trgm}
 * one neither: a trigram index serves {@code LIKE '%...%'} only. A project that needs a fast
 * contains-search adds that pair: a GIN {@code gin_trgm_ops} index on {@code lower(column)} and the
 * predicate rewritten as {@code lower(column) LIKE '%' || q || '%' ESCAPE '\'}, with the query's {@code \},
 * {@code %} and {@code _} escaped. Not with row-level security: {@code LIKE} isn't leakproof, so the policy
 * keeps the index from serving it (house-review TEN-36).
 */
public final class TextSearch {

    private TextSearch() {
    }

    public static Predicate contains(CriteriaBuilder builder, Expression<String> column, String query) {
        return builder.greaterThan(builder.locate(builder.lower(column), query.strip().toLowerCase(Locale.ROOT)), 0);
    }
}
