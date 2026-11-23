package com.example.app.planning;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

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
        return Map.of("fk_plans_todo_id_owner_id", ErrorCode.TODO_DELETED_MEANWHILE);
    }
}
