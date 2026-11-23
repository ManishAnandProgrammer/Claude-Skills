package com.example.app.planning.service;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Decision;
import com.example.app.planning.domain.PlanStatus;
import com.example.app.todo.api.TodoView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class PlanPolicy {

    public Decision create(TodoView todo) {
        if (todo.open()) {
            return Decision.allowed();
        }
        return Decision.refused(ErrorCode.PLAN_TODO_DONE, todo.id());
    }

    public Decision planDays(boolean taken) {
        if (taken) {
            return Decision.refused(ErrorCode.PLAN_OVERLAPS);
        }
        return Decision.allowed();
    }

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

    public Decision changeStatus(PlanStatus status, PlanStatus target) {
        if (status.canBecome(target)) {
            return Decision.allowed();
        }
        return Decision.refused(ErrorCode.PLAN_STATUS_CHANGE_INVALID, status, target);
    }
}
