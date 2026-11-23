package com.example.app.todo;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

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

    @Override
    public Map<String, ErrorCode> byForeignKeyOnWrite() {
        return Map.of(
                "fk_todos_todo_list_id_owner_id", ErrorCode.TODO_LIST_DELETED_MEANWHILE,
                "fk_time_entries_todo_id_owner_id", ErrorCode.TODO_DELETED_MEANWHILE,
                "fk_todo_tags_todo_id_owner_id", ErrorCode.TODO_DELETED_MEANWHILE,
                "fk_todo_tags_tag_id_owner_id", ErrorCode.TAG_DELETED_MEANWHILE);
    }
}
