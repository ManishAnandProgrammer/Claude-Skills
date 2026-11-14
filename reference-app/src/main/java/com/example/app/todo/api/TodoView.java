package com.example.app.todo.api;

import java.util.UUID;

/**
 * A todo as another module sees it. A module contract, never a response body: the consuming module
 * maps it into its own response record (the planning module's {@code PlanTodoResponse}).
 */
public record TodoView(UUID id, String title) {
}
