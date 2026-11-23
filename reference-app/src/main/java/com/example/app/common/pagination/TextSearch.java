package com.example.app.common.pagination;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.util.Locale;

/**
 * {@code position(query in lower(column)) > 0}: unlike {@code LIKE}, the user's {@code %} and {@code _} need
 * no escaping. No index serves it, a {@code pg_trgm} one neither: a trigram index serves {@code LIKE '%...%'}
 * only.
 */
public final class TextSearch {

    private TextSearch() {
    }

    public static Predicate contains(CriteriaBuilder builder, Expression<String> column, String query) {
        return builder.greaterThan(builder.locate(builder.lower(column), query.strip().toLowerCase(Locale.ROOT)), 0);
    }
}
