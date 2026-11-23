package com.example.app.todo.api;

import com.example.app.common.pagination.CursorBatch;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Todos {

    /**
     * Locked until the caller's transaction ends, so the state it checked still holds when it commits: the todo's
     * status change and delete need no lock of their own, as their flushed UPDATE or DELETE waits for this lock.
     */
    TodoView getOwnedTodoViewForUpdate(UUID id, UUID ownerId);

    Optional<TodoView> findOwnedTodoView(UUID id, UUID ownerId);

    List<TodoView> getOwnedTodoViews(Collection<UUID> ids, UUID ownerId);

    CursorBatch<TodoView> getOwnedTodoViewBatch(UUID todoListId, String query, String after, int size, UUID ownerId);
}
