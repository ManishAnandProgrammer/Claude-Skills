package com.example.app.planning.service;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Decision;
import com.example.app.planning.domain.PlanStatus;
import com.example.app.todo.api.TodoView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the owner may do with one of their plans, for the writes and the plan's allowed actions alike. A plan has
 * no second party (another user's plan is the 404 of a missing one, in every query), so its rules are about its
 * state: each lists every unmet precondition, most fundamental first, and the write throws the first. The entity
 * keeps its own guards ({@code Plan.requirePlanned}, {@code Plan.changeStatus}) as invariants; these rules name the
 * same codes, and {@code PlanPolicyTest} pins them. Each takes the fields it reads, so the list's row and the
 * detail's entity are decided by the same method.
 */
@Component
public class PlanPolicy {

    /** New days: only for a PLANNED plan (DONE and CANCELLED are final) whose todo is still open. */
    public Decision reschedule(UUID planId, PlanStatus status, TodoView todo) {
        List<Decision.Refusal> refusals = new ArrayList<>();
        if (status == PlanStatus.DONE) {
            refusals.add(Decision.Refusal.of(ErrorCode.PLAN_DONE, planId));
        }
        if (status == PlanStatus.CANCELLED) {
            refusals.add(Decision.Refusal.of(ErrorCode.PLAN_CANCELLED, planId));
        }
        if (!todo.open()) {
            refusals.add(Decision.Refusal.of(ErrorCode.PLAN_TODO_DONE, todo.id()));
        }
        return Decision.of(refusals);
    }

    /** A move the lifecycle allows ({@link PlanStatus#canBecome}); the arguments name both statuses. */
    public Decision changeStatus(PlanStatus status, PlanStatus target) {
        if (status.canBecome(target)) {
            return Decision.allowed();
        }
        return Decision.refused(ErrorCode.PLAN_STATUS_CHANGE_INVALID, status, target);
    }
}
