package com.example.app.common.pagination;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The predicate's shape against a stub builder; the cursor-walk integration tests run it on PostgreSQL. */
class SeekTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 14);
    private static final Integer MINUTES = 45;
    private static final UUID ID = UUID.fromString("0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d");

    private final CriteriaBuilder builder = mock(CriteriaBuilder.class);
    @SuppressWarnings("unchecked")
    private final Expression<LocalDate> day = mock(Expression.class);
    @SuppressWarnings("unchecked")
    private final Expression<Integer> minutes = mock(Expression.class);
    @SuppressWarnings("unchecked")
    private final Expression<UUID> id = mock(Expression.class);

    private final Predicate dayAfter = mock(Predicate.class);
    private final Predicate dayBefore = mock(Predicate.class);
    private final Predicate dayTie = mock(Predicate.class);
    private final Predicate minutesBefore = mock(Predicate.class);
    private final Predicate minutesTie = mock(Predicate.class);
    private final Predicate idAfter = mock(Predicate.class);
    private final Predicate idBefore = mock(Predicate.class);
    private final Predicate tieThenId = mock(Predicate.class);
    private final Predicate tieThenMinutes = mock(Predicate.class);
    private final Predicate seek = mock(Predicate.class);

    @Test
    @DisplayName("Ascending: a later first key, or the same first key (a tie) and a later id")
    void ascendingSeek() {
        when(builder.greaterThan(day, DAY)).thenReturn(dayAfter);
        when(builder.equal(day, DAY)).thenReturn(dayTie);
        when(builder.greaterThan(id, ID)).thenReturn(idAfter);
        when(builder.and(dayTie, idAfter)).thenReturn(tieThenId);
        when(builder.or(dayAfter, tieThenId)).thenReturn(seek);

        assertThat(Seek.after(builder, false, day, DAY, id, ID)).isSameAs(seek);
        verify(builder, never()).lessThan(day, DAY);
    }

    @Test
    @DisplayName("Descending: an earlier first key, or a tie and an earlier id")
    void descendingSeek() {
        when(builder.lessThan(day, DAY)).thenReturn(dayBefore);
        when(builder.equal(day, DAY)).thenReturn(dayTie);
        when(builder.lessThan(id, ID)).thenReturn(idBefore);
        when(builder.and(dayTie, idBefore)).thenReturn(tieThenId);
        when(builder.or(dayBefore, tieThenId)).thenReturn(seek);

        assertThat(Seek.after(builder, true, day, DAY, id, ID)).isSameAs(seek);
        verify(builder, never()).greaterThan(day, DAY);
    }

    @Test
    @DisplayName("Three keys: each tie falls through to the next key, the id last")
    void threeKeySeek() {
        when(builder.lessThan(day, DAY)).thenReturn(dayBefore);
        when(builder.equal(day, DAY)).thenReturn(dayTie);
        when(builder.lessThan(minutes, MINUTES)).thenReturn(minutesBefore);
        when(builder.equal(minutes, MINUTES)).thenReturn(minutesTie);
        when(builder.lessThan(id, ID)).thenReturn(idBefore);
        when(builder.and(dayTie, minutesBefore)).thenReturn(tieThenMinutes);
        when(builder.and(dayTie, minutesTie, idBefore)).thenReturn(tieThenId);
        when(builder.or(dayBefore, tieThenMinutes, tieThenId)).thenReturn(seek);

        assertThat(Seek.after(builder, true, day, DAY, minutes, MINUTES, id, ID)).isSameAs(seek);
    }

    @Test
    @DisplayName("The order is every seek column, in order, in one direction")
    void orderFollowsTheColumns() {
        Order dayDesc = mock(Order.class);
        Order idDesc = mock(Order.class);
        Order dayAsc = mock(Order.class);
        Order idAsc = mock(Order.class);
        when(builder.desc(day)).thenReturn(dayDesc);
        when(builder.desc(id)).thenReturn(idDesc);
        when(builder.asc(day)).thenReturn(dayAsc);
        when(builder.asc(id)).thenReturn(idAsc);

        List<Order> descending = Seek.order(builder, true, day, id);
        List<Order> ascending = Seek.order(builder, false, day, id);

        assertThat(descending).containsExactly(dayDesc, idDesc);
        assertThat(ascending).containsExactly(dayAsc, idAsc);
    }
}
