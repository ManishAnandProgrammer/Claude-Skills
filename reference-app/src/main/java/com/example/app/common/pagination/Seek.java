package com.example.app.common.pagination;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * {@code (first, last) > (firstValue, lastValue)}, with {@code <} when descending: one row-value
     * comparison, which PostgreSQL uses as one index condition on {@code (…, first, last)}, so the scan
     * starts exactly at the cursor. The expanded form ({@code first >= x AND (first > x OR (first = x AND
     * last > y))}) bounds the index on {@code first} alone: every row that shares the cursor's
     * {@code first} and comes before it is read and thrown away again on each later batch, a whole tie
     * group per batch (a term's start day shared by a thousand enrollments).
     */
    public static <A extends Comparable<? super A>, B extends Comparable<? super B>> Predicate after(
            CriteriaBuilder builder, boolean descending,
            Expression<A> first, A firstValue,
            Expression<B> last, B lastValue) {
        return rowAfter(builder, descending, List.of(first, last), List.of(firstValue, lastValue));
    }

    /** The same with a middle key: {@code (a, b, c) > (a0, b0, c0)}. */
    public static <A extends Comparable<? super A>, B extends Comparable<? super B>, C extends Comparable<? super C>> Predicate after(
            CriteriaBuilder builder, boolean descending,
            Expression<A> first, A firstValue,
            Expression<B> middle, B middleValue,
            Expression<C> last, C lastValue) {
        return rowAfter(builder, descending, List.of(first, middle, last), List.of(firstValue, middleValue, lastValue));
    }

    /** {@code ORDER BY} the seek columns, all in one direction (a row comparison has one direction). */
    public static List<Order> order(CriteriaBuilder builder, boolean descending, Expression<?>... columns) {
        List<Order> orders = new ArrayList<>();
        for (Expression<?> column : columns) {
            orders.add(descending ? builder.desc(column) : builder.asc(column));
        }
        return orders;
    }

    /**
     * JPA's Criteria API has no row value, so the comparison is Hibernate's SQL fragment with the columns
     * and the cursor's values as its arguments. Each value goes in through {@code value(...)}, a bound
     * parameter as {@code lessThan(column, value)} makes one: {@code literal(...)} would splice it into the
     * SQL text (a new statement per cursor, and an {@code Instant} as a zone-less {@code timestamp '...'} that
     * the session's time zone shifts).
     */
    private static Predicate rowAfter(CriteriaBuilder builder, boolean descending, List<Expression<?>> columns, List<Object> values) {
        HibernateCriteriaBuilder hibernate = (HibernateCriteriaBuilder) builder;
        String row = String.join(", ", Collections.nCopies(columns.size(), "?"));
        String pattern = "(" + row + ") " + (descending ? "<" : ">") + " (" + row + ")";
        List<Expression<?>> arguments = new ArrayList<>(columns);
        for (Object value : values) {
            arguments.add(hibernate.value(value));
        }
        return builder.isTrue(hibernate.sql(pattern, Boolean.class, arguments.toArray(new Expression<?>[0])));
    }
}
