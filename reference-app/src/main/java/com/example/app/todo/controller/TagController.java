package com.example.app.todo.controller;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.todo.controller.api.TagApi;
import com.example.app.todo.dto.request.TagCreateRequest;
import com.example.app.todo.dto.request.TagFilter;
import com.example.app.todo.dto.request.TagUpdateRequest;
import com.example.app.todo.dto.response.TagResponse;
import com.example.app.todo.service.TagService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/{version}/tags")
@RequiredArgsConstructor
class TagController implements TagApi {

    private final TagService service;

    @Override
    @GetMapping(version = "v1+")
    public PaginationWrapper<TagResponse> all(@Valid TagFilter filter) {
        return service.findByFilter(filter);
    }

    @Override
    @GetMapping(path = "/{id}", version = "v1+")
    public ObjectWrapper<TagResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectWrapper<TagResponse> create(@RequestBody @Valid TagCreateRequest request) {
        return service.create(request);
    }

    @Override
    @PutMapping(path = "/{id}", version = "v1+")
    public ObjectWrapper<TagResponse> update(@PathVariable UUID id, @RequestBody @Valid TagUpdateRequest request) {
        return service.update(id, request);
    }

    @Override
    @DeleteMapping(path = "/{id}", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @Override
    @GetMapping(path = "/dropdown", version = "v1+")
    @PreAuthorize("isAuthenticated()")
    public CursorWrapper<DropdownItem> dropdown(@Valid DropdownFilter filter) {
        return service.dropdown(filter);
    }
}
