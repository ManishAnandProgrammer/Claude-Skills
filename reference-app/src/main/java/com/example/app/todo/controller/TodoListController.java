package com.example.app.todo.controller;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.todo.controller.api.TodoListApi;
import com.example.app.todo.dto.request.TodoListCreateRequest;
import com.example.app.todo.dto.request.TodoListFilter;
import com.example.app.todo.dto.request.TodoListUpdateRequest;
import com.example.app.todo.dto.response.TodoListResponse;
import com.example.app.todo.service.TodoListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
class TodoListController implements TodoListApi {

    private final TodoListService service;

    @Override
    @GetMapping("/api/v1/todo-lists")
    public PaginationWrapper<TodoListResponse> all(@Valid TodoListFilter filter) {
        return service.findByFilter(filter);
    }

    @Override
    @GetMapping("/api/v1/todo-lists/{id}")
    public ObjectWrapper<TodoListResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Override
    @PostMapping("/api/v1/todo-lists")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectWrapper<TodoListResponse> create(@RequestBody @Valid TodoListCreateRequest request) {
        return service.create(request);
    }

    @Override
    @PutMapping("/api/v1/todo-lists/{id}")
    public ObjectWrapper<TodoListResponse> update(@PathVariable UUID id, @RequestBody @Valid TodoListUpdateRequest request) {
        return service.update(id, request);
    }

    @Override
    @DeleteMapping("/api/v1/todo-lists/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @Override
    @GetMapping("/api/v1/todo-lists/dropdown")
    @PreAuthorize("isAuthenticated()")
    public CursorWrapper<DropdownItem> dropdown(@Valid DropdownFilter filter) {
        return service.dropdown(filter);
    }
}
