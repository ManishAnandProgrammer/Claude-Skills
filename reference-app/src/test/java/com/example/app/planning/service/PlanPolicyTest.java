package com.example.app.planning.service;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Decision;
import com.example.app.planning.domain.PlanStatus;
import com.example.app.todo.api.TodoView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PlanPolicyTest {

    private static final PlanPolicy POLICY = new PlanPolicy();
    private static final UUID PLAN = UUID.randomUUID();
    private static final TodoView OPEN_TODO = new TodoView(UUID.randomUUID(), "Open", true);
    private static final TodoView DONE_TODO = new TodoView(UUID.randomUUID(), "Done", false);

    static Stream<Arguments> reschedules() {
        return Stream.of(
                Arguments.of(PlanStatus.PLANNED, OPEN_TODO, List.of()),
                Arguments.of(PlanStatus.PLANNED, DONE_TODO, List.of(
                        Decision.Refusal.of(ErrorCode.PLAN_TODO_DONE, DONE_TODO.id()))),
                Arguments.of(PlanStatus.DONE, OPEN_TODO, List.of(
                        Decision.Refusal.of(ErrorCode.PLAN_DONE, PLAN))),
                Arguments.of(PlanStatus.CANCELLED, OPEN_TODO, List.of(
                        Decision.Refusal.of(ErrorCode.PLAN_CANCELLED, PLAN))),
                Arguments.of(PlanStatus.DONE, DONE_TODO, List.of(
                        Decision.Refusal.of(ErrorCode.PLAN_DONE, PLAN),
                        Decision.Refusal.of(ErrorCode.PLAN_TODO_DONE, DONE_TODO.id()))),
                Arguments.of(PlanStatus.CANCELLED, DONE_TODO, List.of(
                        Decision.Refusal.of(ErrorCode.PLAN_CANCELLED, PLAN),
                        Decision.Refusal.of(ErrorCode.PLAN_TODO_DONE, DONE_TODO.id()))));
    }

    @ParameterizedTest(name = "{0} plan, todo open {1}: {2}")
    @MethodSource("reschedules")
    @DisplayName("New days: every unmet precondition at once, the plan's own state first")
    void reschedule(PlanStatus status, TodoView todo, List<Decision.Refusal> refusals) {
        Decision decision = POLICY.reschedule(PLAN, status, todo);

        assertThat(decision.isAllowed()).isEqualTo(refusals.isEmpty());
        assertThat(decision.refusals()).containsExactlyElementsOf(refusals);
    }

    @ParameterizedTest(name = "todo open {0}")
    @MethodSource("createsByTodo")
    @DisplayName("A new plan: only for an open todo, a done one refused naming it (the create and its todo options alike)")
    void create(TodoView todo, List<Decision.Refusal> refusals) {
        assertThat(POLICY.create(todo).refusals()).containsExactlyElementsOf(refusals);
    }

    static Stream<Arguments> createsByTodo() {
        return Stream.of(
                Arguments.of(OPEN_TODO, List.of()),
                Arguments.of(DONE_TODO, List.of(Decision.Refusal.of(ErrorCode.PLAN_TODO_DONE, DONE_TODO.id()))));
    }

    @ParameterizedTest(name = "taken {0}")
    @MethodSource("plannedDays")
    @DisplayName("Days of the availability read: taken ones refused as the overlap constraint answers, with no arguments")
    void planDays(boolean taken, List<Decision.Refusal> refusals) {
        assertThat(POLICY.planDays(taken).refusals()).containsExactlyElementsOf(refusals);
    }

    static Stream<Arguments> plannedDays() {
        return Stream.of(
                Arguments.of(false, List.of()),
                Arguments.of(true, List.of(Decision.Refusal.of(ErrorCode.PLAN_OVERLAPS))));
    }

    static Stream<Arguments> statusChanges() {
        return Stream.of(
                Arguments.of(PlanStatus.PLANNED, PlanStatus.DONE, true),
                Arguments.of(PlanStatus.PLANNED, PlanStatus.CANCELLED, true),
                Arguments.of(PlanStatus.PLANNED, PlanStatus.PLANNED, false),
                Arguments.of(PlanStatus.DONE, PlanStatus.CANCELLED, false),
                Arguments.of(PlanStatus.CANCELLED, PlanStatus.DONE, false));
    }

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @MethodSource("statusChanges")
    @DisplayName("A status change: the lifecycle's moves; any other refused naming both statuses")
    void changeStatus(PlanStatus status, PlanStatus target, boolean allowed) {
        Decision decision = POLICY.changeStatus(status, target);

        List<Decision.Refusal> expected = allowed
                ? List.of()
                : List.of(Decision.Refusal.of(ErrorCode.PLAN_STATUS_CHANGE_INVALID, status, target));
        assertThat(decision.refusals()).containsExactlyElementsOf(expected);
    }
}
