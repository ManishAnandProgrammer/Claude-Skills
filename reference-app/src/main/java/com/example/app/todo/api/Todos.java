package com.example.app.todo.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The todos, for the modules whose records refer to one by id (a plan). Every lookup is scoped to an
 * owner: another owner's todo is 404 {@code TODO_NOT_FOUND}, like a missing one.
 */
public interface Todos {

    /**
     * The owner's todo, locked until the caller's transaction ends, for a write that checks the todo's
     * state (a plan is only for an open todo). The todo's own writes (its status change, its delete) wait
     * for that write, so the state it checked still holds when it commits. Joins the caller's
     * transaction, which must exist. 404 {@code TODO_NOT_FOUND} when it is missing or another owner's.
     */
    TodoView getOwnedTodoViewForUpdate(UUID id, UUID ownerId);

    /**
     * The owner's todo, for a record that refers to it and was read a moment before; empty when it is
     * missing or another owner's, so the caller decides what that means for its own record.
     */
    Optional<TodoView> findOwnedTodoView(UUID id, UUID ownerId);

    /**
     * The owner's todos among {@code ids}, in one statement, for a batch of records that refer to them
     * (a page of plans); unknown and another owner's ids are simply absent.
     */
    List<TodoView> getOwnedTodoViews(Collection<UUID> ids, UUID ownerId);
}
