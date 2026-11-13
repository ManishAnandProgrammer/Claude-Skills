package com.example.app.todo.repository.custom;

import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.response.TodoSummaryResponse;

import java.util.UUID;

public interface CustomTodoRepository {

    CursorWrapper<TodoSummaryResponse> findByFilter(TodoFilter filter, UUID ownerId, int size);
}
