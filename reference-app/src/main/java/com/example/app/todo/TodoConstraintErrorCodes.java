package com.example.app.todo;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The list and tag names unique per owner, and the foreign keys that refuse a delete while children
 * point at it: a list with todos, a todo with time entries. Inserts never trip those foreign keys:
 * the services lock the parent for the write.
 */
@Component
class TodoConstraintErrorCodes implements ConstraintErrorCodes {

    @Override
    public Map<String, ErrorCode> byConstraintName() {
        return Map.of(
                "uq_todo_lists_owner_id_name", ErrorCode.TODO_LIST_NAME_TAKEN,
                "fk_todos_todo_list_id_owner_id", ErrorCode.TODO_LIST_IN_USE,
                "fk_time_entries_todo_id_owner_id", ErrorCode.TODO_IN_USE,
                "uq_tags_owner_id_name", ErrorCode.TAG_NAME_TAKEN);
    }
}
