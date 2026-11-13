package com.example.app.todo.controller;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.todo.controller.api.TodoApi;
import com.example.app.todo.dto.request.TodoCreateRequest;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.request.TodoStatusRequest;
import com.example.app.todo.dto.request.TodoUpdateRequest;
import com.example.app.todo.dto.response.TodoResponse;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import com.example.app.todo.service.TodoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
class TodoController implements TodoApi {

    private final TodoService service;

    @Override
    @GetMapping("/api/v1/todos")
    public CursorWrapper<TodoSummaryResponse> all(@Valid TodoFilter filter) {
        return service.findByFilter(filter);
    }

    @Override
    @GetMapping("/api/v1/todos/{id}")
    public ObjectWrapper<TodoResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Override
    @PostMapping("/api/v1/todos")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectWrapper<TodoResponse> create(@RequestBody @Valid TodoCreateRequest request) {
        return service.create(request);
    }

    @Override
    @PutMapping("/api/v1/todos/{id}")
    public ObjectWrapper<TodoResponse> update(@PathVariable UUID id, @RequestBody @Valid TodoUpdateRequest request) {
        return service.update(id, request);
    }

    @Override
    @PatchMapping("/api/v1/todos/{id}/status")
    public ObjectWrapper<TodoResponse> changeStatus(@PathVariable UUID id, @RequestBody @Valid TodoStatusRequest request) {
        return service.changeStatus(id, request);
    }

    @Override
    @DeleteMapping("/api/v1/todos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
