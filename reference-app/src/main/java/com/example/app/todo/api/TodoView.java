package com.example.app.todo.api;

import java.util.UUID;

/**
 * A todo as another module sees it. A module contract, never a response body: the consuming module
 * maps it into its own response record (the planning module's {@code PlanTodoResponse}).
 *
 * <p>{@code open} is the todo module's own reading of its status (not DONE yet): a module that checks
 * a todo's state asks this, so {@code TodoStatus} stays in {@code todo.domain} (ModuleBoundaryArchTest:
 * an {@code api} package exposes no domain type) and the todo module alone decides what open means.</p>
 */
public record TodoView(UUID id, String title, boolean open) {
}
