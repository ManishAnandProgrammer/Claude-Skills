package com.example.app.todo.controller;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.todo.controller.api.TodoApi;
import com.example.app.todo.dto.request.TimeEntryCreateRequest;
import com.example.app.todo.dto.request.TimeEntryFilter;
import com.example.app.todo.dto.request.TimeEntryUpdateRequest;
import com.example.app.todo.dto.request.TodoCreateRequest;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.request.TodoStatusRequest;
import com.example.app.todo.dto.request.TodoUpdateRequest;
import com.example.app.todo.dto.response.TimeEntryResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/{version}/todos")
@RequiredArgsConstructor
class TodoController implements TodoApi {

    private final TodoService service;

    @Override
    @GetMapping(version = "v1+")
    public CursorWrapper<TodoSummaryResponse> all(@Valid TodoFilter filter) {
        return service.findByFilter(filter);
    }

    @Override
    @GetMapping(path = "/{id}", version = "v1+")
    public ObjectWrapper<TodoResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectWrapper<TodoResponse> create(@RequestBody @Valid TodoCreateRequest request) {
        return service.create(request);
    }

    @Override
    @PutMapping(path = "/{id}", version = "v1+")
    public ObjectWrapper<TodoResponse> update(@PathVariable UUID id, @RequestBody @Valid TodoUpdateRequest request) {
        return service.update(id, request);
    }

    @Override
    @PatchMapping(path = "/{id}/status", version = "v1+")
    public ObjectWrapper<TodoResponse> changeStatus(@PathVariable UUID id, @RequestBody @Valid TodoStatusRequest request) {
        return service.changeStatus(id, request);
    }

    @Override
    @DeleteMapping(path = "/{id}", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @Override
    @GetMapping(path = "/{todoId}/time-entries", version = "v1+")
    public CursorWrapper<TimeEntryResponse> timeEntries(@PathVariable UUID todoId, @Valid TimeEntryFilter filter) {
        return service.findTimeEntries(todoId, filter);
    }

    @Override
    @GetMapping(path = "/{todoId}/time-entries/{id}", version = "v1+")
    public ObjectWrapper<TimeEntryResponse> getTimeEntry(@PathVariable UUID todoId, @PathVariable UUID id) {
        return service.findTimeEntry(todoId, id);
    }

    @Override
    @PostMapping(path = "/{todoId}/time-entries", version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectWrapper<TimeEntryResponse> createTimeEntry(@PathVariable UUID todoId,
                                                            @RequestBody @Valid TimeEntryCreateRequest request) {
        return service.createTimeEntry(todoId, request);
    }

    @Override
    @PutMapping(path = "/{todoId}/time-entries/{id}", version = "v1+")
    public ObjectWrapper<TimeEntryResponse> updateTimeEntry(@PathVariable UUID todoId, @PathVariable UUID id,
                                                            @RequestBody @Valid TimeEntryUpdateRequest request) {
        return service.updateTimeEntry(todoId, id, request);
    }

    @Override
    @DeleteMapping(path = "/{todoId}/time-entries/{id}", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTimeEntry(@PathVariable UUID todoId, @PathVariable UUID id) {
        service.deleteTimeEntry(todoId, id);
    }
}
