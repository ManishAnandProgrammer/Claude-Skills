package com.example.app.common.pagination;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;

import java.util.List;

/**
 * What a list reads: rows of {@code row}, built by {@code projection} from the {@code entity}s that
 * meet {@code conditions}. Only the selected columns, never entities. {@link OffsetPages} and
 * {@link KeysetPages} add the order and the window.
 */
public record ProjectionQuery<T, R>(Class<T> entity, Class<R> row, Projection<T, R> projection, Conditions<T> conditions) {

    @FunctionalInterface
    public interface Projection<T, R> {
        Selection<R> select(CriteriaBuilder builder, Root<T> root);
    }

    @FunctionalInterface
    public interface Conditions<T> {
        List<Predicate> build(CriteriaBuilder builder, Root<T> root);
    }
}
