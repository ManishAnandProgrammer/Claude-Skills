package com.example.app.todo.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * A todo's list by name. The detail and the writes that hold no lock read it with the todo in one statement
 * (TodoRepository.findWithTodoListById); the writes that lock the todo read the loaded todo's list
 * afterwards (the lock keeps it there); a list page reads the batch's names in a second statement
 * (TodoListService.findOwnedTodoListNames).
 */
@Schema(description = "The list a todo is in")
public record TodoTodoListResponse(
        @Schema(description = "List id", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d") UUID id,
        @Schema(description = "Its name", example = "Groceries") String name) {
}
