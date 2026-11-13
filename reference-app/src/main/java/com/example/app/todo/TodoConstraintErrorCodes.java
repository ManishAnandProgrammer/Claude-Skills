package com.example.app.todo;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The list name unique per owner, and the foreign key that refuses a list's delete while todos point
 * at it. Inserts never trip that foreign key: the services lock the parent list for the write.
 */
@Component
class TodoConstraintErrorCodes implements ConstraintErrorCodes {

    @Override
    public Map<String, ErrorCode> byConstraintName() {
        return Map.of(
                "uq_todo_lists_owner_id_name", ErrorCode.TODO_LIST_NAME_TAKEN,
                "fk_todos_todo_list_id_owner_id", ErrorCode.TODO_LIST_IN_USE);
    }
}
