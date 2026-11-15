package com.example.app.planning;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The constraints of changeset 0011, mapped here because this module owns them: the overlap a plan
 * create can trip, and the foreign key to the todo. Tripped by a todo's delete, it answers the todo
 * module's {@code TODO_IN_USE}; tripped by a plan's insert (a todo deleted after the create read it),
 * {@code TODO_GONE}. The create locks its todo, so its insert can't trip the key today; the mapping
 * stays, so a write added later without the lock answers 404, never 500. The codes belong to the todo,
 * the mapping to the module whose table holds the constraint.
 */
@Component
class PlanningConstraintErrorCodes implements ConstraintErrorCodes {

    @Override
    public Map<String, ErrorCode> byConstraintName() {
        return Map.of(
                "ex_plans_owner_id_dates", ErrorCode.PLAN_OVERLAPS,
                "fk_plans_todo_id_owner_id", ErrorCode.TODO_IN_USE);
    }

    @Override
    public Map<String, ErrorCode> byForeignKeyOnWrite() {
        return Map.of("fk_plans_todo_id_owner_id", ErrorCode.TODO_GONE);
    }
}
