package com.example.app.todo.mapper;

import com.example.app.todo.domain.TodoList;
import com.example.app.todo.dto.request.TodoListCreateRequest;
import com.example.app.todo.dto.request.TodoListUpdateRequest;
import com.example.app.todo.dto.response.TodoListResponse;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TodoListMapper {

    public TodoList toEntity(TodoListCreateRequest request, UUID ownerId) {
        return TodoList.builder()
                .name(request.name().strip())
                .ownerId(ownerId)
                .build();
    }

    public void update(TodoList todoList, TodoListUpdateRequest request) {
        todoList.setName(request.name().strip());
    }

    public TodoListResponse toResponse(TodoList todoList) {
        return new TodoListResponse(
                todoList.getId(),
                todoList.getName(),
                todoList.getOwnerId(),
                todoList.getCreatedAt(),
                todoList.getUpdatedAt(),
                todoList.getCreatedBy(),
                todoList.getUpdatedBy(),
                todoList.getVersion());
    }
}
