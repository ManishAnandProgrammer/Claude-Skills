package com.example.app.common.pagination;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaExpression;
import org.hibernate.query.criteria.JpaOrder;
import org.hibernate.query.criteria.JpaPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeekTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 14);
    private static final Integer MINUTES = 45;
    private static final UUID ID = UUID.fromString("0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d");

    private final HibernateCriteriaBuilder builder = mock(HibernateCriteriaBuilder.class);
    @SuppressWarnings("unchecked")
    private final Expression<LocalDate> day = mock(Expression.class);
    @SuppressWarnings("unchecked")
    private final Expression<Integer> minutes = mock(Expression.class);
    @SuppressWarnings("unchecked")
    private final Expression<UUID> id = mock(Expression.class);
    @SuppressWarnings("unchecked")
    private final JpaExpression<Object> dayValue = mock(JpaExpression.class);
    @SuppressWarnings("unchecked")
    private final JpaExpression<Object> minutesValue = mock(JpaExpression.class);
    @SuppressWarnings("unchecked")
    private final JpaExpression<Object> idValue = mock(JpaExpression.class);
    @SuppressWarnings("unchecked")
    private final JpaExpression<Boolean> comparison = mock(JpaExpression.class);
    private final JpaPredicate seek = mock(JpaPredicate.class);

    @Test
    @DisplayName("Ascending: (first, id) > (the cursor's first, the cursor's id), the values bound, never inlined")
    void ascendingSeek() {
        givenTheValues();
        when(builder.sql("(?, ?) > (?, ?)", Boolean.class, day, id, dayValue, idValue)).thenReturn(comparison);
        when(builder.isTrue(comparison)).thenReturn(seek);

        assertThat(Seek.after(builder, false, day, DAY, id, ID)).isSameAs(seek);
        verify(builder, never()).literal(any());
    }

    @Test
    @DisplayName("Descending: (first, id) < (the cursor's first, the cursor's id)")
    void descendingSeek() {
        givenTheValues();
        when(builder.sql("(?, ?) < (?, ?)", Boolean.class, day, id, dayValue, idValue)).thenReturn(comparison);
        when(builder.isTrue(comparison)).thenReturn(seek);

        assertThat(Seek.after(builder, true, day, DAY, id, ID)).isSameAs(seek);
        verify(builder, never()).literal(any());
    }

    @Test
    @DisplayName("Three keys: one row of three, the id last")
    void threeKeySeek() {
        givenTheValues();
        when(builder.sql("(?, ?, ?) < (?, ?, ?)", Boolean.class, day, minutes, id, dayValue, minutesValue, idValue))
                .thenReturn(comparison);
        when(builder.isTrue(comparison)).thenReturn(seek);

        assertThat(Seek.after(builder, true, day, DAY, minutes, MINUTES, id, ID)).isSameAs(seek);
    }

    @Test
    @DisplayName("The order is every seek column, in order, in one direction")
    void orderFollowsTheColumns() {
        JpaOrder dayDesc = mock(JpaOrder.class);
        JpaOrder idDesc = mock(JpaOrder.class);
        JpaOrder dayAsc = mock(JpaOrder.class);
        JpaOrder idAsc = mock(JpaOrder.class);
        when(builder.desc(day)).thenReturn(dayDesc);
        when(builder.desc(id)).thenReturn(idDesc);
        when(builder.asc(day)).thenReturn(dayAsc);
        when(builder.asc(id)).thenReturn(idAsc);

        List<Order> descending = Seek.order(builder, true, day, id);
        List<Order> ascending = Seek.order(builder, false, day, id);

        assertThat(descending).containsExactly(dayDesc, idDesc);
        assertThat(ascending).containsExactly(dayAsc, idAsc);
    }

    private void givenTheValues() {
        when(builder.value((Object) DAY)).thenReturn(dayValue);
        when(builder.value((Object) MINUTES)).thenReturn(minutesValue);
        when(builder.value((Object) ID)).thenReturn(idValue);
    }
}
