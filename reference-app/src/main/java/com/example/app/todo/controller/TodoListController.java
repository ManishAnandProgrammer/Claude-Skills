package com.example.app.todo.controller;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownFor;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Requires;
import com.example.app.common.security.SignedIn;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/{version}/todo-lists")
@RequiredArgsConstructor
class TodoListController implements TodoListApi {

    private final TodoListService service;

    @Override
    @GetMapping(version = "v1+")
    @Requires(Permission.TODO_LIST_READ_OWN)
    public PaginationWrapper<TodoListResponse> all(@Valid TodoListFilter filter) {
        return service.findByFilter(filter);
    }

    @Override
    @GetMapping(path = "/{id}", version = "v1+")
    @Requires(Permission.TODO_LIST_READ_OWN)
    public ObjectWrapper<TodoListResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Permission.TODO_LIST_CREATE_OWN)
    public ObjectWrapper<TodoListResponse> create(@RequestBody @Valid TodoListCreateRequest request) {
        return service.create(request);
    }

    @Override
    @PutMapping(path = "/{id}", version = "v1+")
    @Requires(Permission.TODO_LIST_EDIT_OWN)
    public ObjectWrapper<TodoListResponse> update(@PathVariable UUID id, @RequestBody @Valid TodoListUpdateRequest request) {
        return service.update(id, request);
    }

    @Override
    @DeleteMapping(path = "/{id}", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires(Permission.TODO_LIST_DELETE_OWN)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @Override
    @GetMapping(path = "/dropdown", version = "v1+")
    @SignedIn
    public CursorWrapper<DropdownItem> dropdown(@RequestParam(DropdownFor.PARAMETER) Permission action,
                                                @Valid DropdownFilter filter) {
        return service.dropdown(action, filter);
    }
}
