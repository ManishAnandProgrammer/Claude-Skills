package com.example.app.planning.domain;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            PLANNED   | DONE      | true
            PLANNED   | CANCELLED | true
            PLANNED   | PLANNED   | false
            DONE      | PLANNED   | false
            DONE      | DONE      | false
            DONE      | CANCELLED | false
            CANCELLED | PLANNED   | false
            CANCELLED | DONE      | false
            CANCELLED | CANCELLED | false
            """)
    @DisplayName("The lifecycle's whole matrix: PLANNED becomes DONE or CANCELLED, nothing else moves")
    void theLifecycleMatrix(PlanStatus from, PlanStatus target, boolean allowed) {
        assertThat(from.canBecome(target)).isEqualTo(allowed);
    }

    @Test
    @DisplayName("A refused move is the client's: 409 PLAN_STATUS_CHANGE_INVALID, the status unchanged")
    void aRefusedMoveIsAConflict() {
        Plan plan = plan();
        plan.changeStatus(PlanStatus.CANCELLED, null);

        assertThatExceptionOfType(AppException.class)
                .isThrownBy(() -> plan.changeStatus(PlanStatus.DONE, LocalDate.of(2026, 11, 21)))
                .satisfies(refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.PLAN_STATUS_CHANGE_INVALID));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.CANCELLED);
        assertThat(plan.getDoneOn()).isNull();
    }

    @Test
    @DisplayName("DONE takes its day; a day before the first is PLAN_DONE_ON_BEFORE_STARTS_ON, the plan still PLANNED")
    void doneTakesItsDay() {
        Plan plan = plan();

        assertThatExceptionOfType(AppException.class)
                .isThrownBy(() -> plan.changeStatus(PlanStatus.DONE, LocalDate.of(2026, 11, 19)))
                .satisfies(refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.PLAN_DONE_ON_BEFORE_STARTS_ON));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.PLANNED);
        plan.changeStatus(PlanStatus.DONE, LocalDate.of(2026, 11, 20));
        assertThat(plan.getDoneOn()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    @DisplayName("A final plan's reschedule is refused with its status's code, the days unchanged")
    void aFinalPlanIsNotRescheduled() {
        Plan done = plan();
        done.changeStatus(PlanStatus.DONE, LocalDate.of(2026, 11, 21));
        Plan cancelled = plan();
        cancelled.changeStatus(PlanStatus.CANCELLED, null);

        assertThatExceptionOfType(AppException.class)
                .isThrownBy(() -> done.reschedule(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2)))
                .satisfies(refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.PLAN_DONE));
        assertThatExceptionOfType(AppException.class)
                .isThrownBy(() -> cancelled.reschedule(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2)))
                .satisfies(refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.PLAN_CANCELLED));
        assertThat(done.getStartsOn()).isEqualTo(LocalDate.of(2026, 11, 20));
    }
}
