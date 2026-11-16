package com.example.app.todo.dto.internal;

import java.util.UUID;

/**
 * A list's name for a page of todos (TodoService's second step): a same-module parent's view, read as a
 * constructor projection of the two columns. It never goes on the wire: the row maps it into
 * {@code TodoTodoListResponse}.
 */
public record TodoListName(UUID id, String name) {
}
