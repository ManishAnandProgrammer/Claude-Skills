package com.example.app.common.pagination;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;

/**
 * The two halves of keyset paging in a Criteria query: "the rows after this position" and the
 * order that makes it true. The query seeks past the cursor's position instead of skipping rows, so
 * batch 1,000 costs what batch 1 costs. Pass the same columns, in the same order, to both; the last
 * one must be unique (the id) and every key non-null, so each row has exactly one position.
 */
public final class Seek {

    private Seek() {
    }

    /** {@code first > firstValue OR (first = firstValue AND last > lastValue)}, with {@code <} when descending. */
    public static <A extends Comparable<? super A>, B extends Comparable<? super B>> Predicate after(
            CriteriaBuilder builder, boolean descending,
            Expression<A> first, A firstValue,
            Expression<B> last, B lastValue) {
        return builder.or(
                beyond(builder, descending, first, firstValue),
                builder.and(builder.equal(first, firstValue), beyond(builder, descending, last, lastValue)));
    }

    /** The same with a middle key: {@code a > a0 OR (a = a0 AND b > b0) OR (a = a0 AND b = b0 AND c > c0)}. */
    public static <A extends Comparable<? super A>, B extends Comparable<? super B>, C extends Comparable<? super C>> Predicate after(
            CriteriaBuilder builder, boolean descending,
            Expression<A> first, A firstValue,
            Expression<B> middle, B middleValue,
            Expression<C> last, C lastValue) {
        return builder.or(
                beyond(builder, descending, first, firstValue),
                builder.and(builder.equal(first, firstValue), beyond(builder, descending, middle, middleValue)),
                builder.and(builder.equal(first, firstValue), builder.equal(middle, middleValue),
                        beyond(builder, descending, last, lastValue)));
    }

    /** {@code ORDER BY} the seek columns, all in one direction. */
    public static List<Order> order(CriteriaBuilder builder, boolean descending, Expression<?>... columns) {
        List<Order> orders = new ArrayList<>();
        for (Expression<?> column : columns) {
            orders.add(descending ? builder.desc(column) : builder.asc(column));
        }
        return orders;
    }

    private static <A extends Comparable<? super A>> Predicate beyond(CriteriaBuilder builder, boolean descending,
                                                                      Expression<A> column, A value) {
        return descending ? builder.lessThan(column, value) : builder.greaterThan(column, value);
    }
}
