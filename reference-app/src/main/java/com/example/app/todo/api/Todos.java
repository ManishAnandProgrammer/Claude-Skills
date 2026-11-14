package com.example.app.todo.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The todos, for the modules whose records refer to one by id (a plan). Every lookup is scoped to an
 * owner: another owner's todo is 404 {@code TODO_NOT_FOUND}, like a missing one.
 */
public interface Todos {

    /** The owner's todo; 404 {@code TODO_NOT_FOUND} when it is missing or another owner's. */
    TodoView getOwnedTodoView(UUID id, UUID ownerId);

    /**
     * The owner's todos among {@code ids}, in one statement, for a batch of records that refer to them
     * (a page of plans); unknown and another owner's ids are simply absent.
     */
    List<TodoView> getOwnedTodoViews(Collection<UUID> ids, UUID ownerId);
}
