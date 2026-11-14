package com.example.app.planning;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The constraints of changeset 0011, mapped here because this module owns them: the overlap a plan
 * create can trip, and the foreign key that refuses deleting a todo that still has plans. That one
 * answers the todo module's {@code TODO_IN_USE}: the code belongs to the refused delete, the mapping to
 * the module whose table holds the constraint.
 */
@Component
class PlanningConstraintErrorCodes implements ConstraintErrorCodes {

    @Override
    public Map<String, ErrorCode> byConstraintName() {
        return Map.of(
                "ex_plans_owner_id_dates", ErrorCode.PLAN_OVERLAPS,
                "fk_plans_todo_id_owner_id", ErrorCode.TODO_IN_USE);
    }
}
