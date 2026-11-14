package com.example.app.todo;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The list and tag names unique per owner, and the foreign keys by the side that trips them. A delete
 * of a parent with children is refused: a list with todos, a todo with time entries. A child written
 * while its parent is deleted (the service read the parent, the delete committed before the insert)
 * answers the parent's not-found code: nothing locks the parent for the write, and the foreign key
 * keeps the data right on its own. A tag's delete cascades to its links, so that key trips only on
 * the write side; a todo's delete removes its links first, under the todo's lock.
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

    @Override
    public Map<String, ErrorCode> byForeignKeyOnWrite() {
        return Map.of(
                "fk_todos_todo_list_id_owner_id", ErrorCode.TODO_LIST_GONE,
                "fk_time_entries_todo_id_owner_id", ErrorCode.TODO_GONE,
                "fk_todo_tags_todo_id_owner_id", ErrorCode.TODO_GONE,
                "fk_todo_tags_tag_id_owner_id", ErrorCode.TAG_GONE);
    }
}
