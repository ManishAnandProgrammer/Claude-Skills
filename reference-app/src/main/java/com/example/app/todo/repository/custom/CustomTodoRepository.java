package com.example.app.todo.repository.custom;

import com.example.app.common.pagination.CursorBatch;
import com.example.app.todo.api.TodoView;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.dto.request.TodoDropdownFilter;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import org.springframework.data.jpa.domain.PredicateSpecification;

import java.util.Optional;
import java.util.UUID;

public interface CustomTodoRepository {

    CursorBatch<TodoSummaryResponse> findByFilter(TodoFilter filter, PredicateSpecification<Todo> scope, int size);

    /**
     * Locked only when {@code scope} allows it (the condition is the statement's own), so nobody else's request
     * queues behind the owner's writers. Every write that changes the todo's member rows (tags, attachments), and
     * its delete, takes it before touching them: a second writer with the same version waits here and answers 409,
     * instead of colliding with the first on a member row or deadlocking with it.
     */
    Optional<Todo> findForUpdate(UUID id, PredicateSpecification<Todo> scope);

    CursorBatch<TodoView> findDropdownViews(TodoDropdownFilter filter, PredicateSpecification<Todo> scope, int size);
}
