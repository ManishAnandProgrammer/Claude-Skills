package com.example.app.planning.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PlanTest {

    private static Plan plan() {
        return Plan.builder().todoId(UUID.randomUUID()).ownerId(UUID.randomUUID())
                .startsOn(LocalDate.of(2026, 11, 20)).endsOn(LocalDate.of(2026, 11, 22)).build();
    }

    @Test
    @DisplayName("A reschedule moves both ends; a one-day range is the same day twice")
    void rescheduleMovesBothEnds() {
        Plan plan = plan();

        plan.reschedule(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1));

        assertThat(plan.getStartsOn()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(plan.getEndsOn()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    @DisplayName("An inverted range is a caller's bug: IllegalStateException, the days unchanged")
    void anInvertedRangeIsRefused() {
        Plan plan = plan();

        assertThatIllegalStateException().isThrownBy(() -> plan.reschedule(LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 1)));
        assertThat(plan.getStartsOn()).isEqualTo(LocalDate.of(2026, 11, 20));
    }
}
