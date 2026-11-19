package com.example.app.todo.repository.custom;

import com.example.app.common.pagination.CursorBatch;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import org.springframework.data.jpa.domain.PredicateSpecification;

public interface CustomTodoRepository {

    /** {@code scope} is the caller's reach (TodoPolicy.listScope), one more condition of the same query. */
    CursorBatch<TodoSummaryResponse> findByFilter(TodoFilter filter, PredicateSpecification<Todo> scope, int size);
}
