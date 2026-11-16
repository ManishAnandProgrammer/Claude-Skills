package com.example.app.planning.controller;

import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.planning.controller.api.PlanApi;
import com.example.app.planning.dto.request.PlanCreateRequest;
import com.example.app.planning.dto.request.PlanFilter;
import com.example.app.planning.dto.request.PlanStatusRequest;
import com.example.app.planning.dto.request.PlanUpdateRequest;
import com.example.app.planning.dto.response.PlanResponse;
import com.example.app.planning.service.PlanService;
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
@RequestMapping("/api/{version}/plans")
@RequiredArgsConstructor
class PlanController implements PlanApi {

    private final PlanService service;

    @Override
    @GetMapping(version = "v1+")
    public CursorWrapper<PlanResponse> all(@Valid PlanFilter filter) {
        return service.findByFilter(filter);
    }

    @Override
    @GetMapping(path = "/{id}", version = "v1+")
    public ObjectWrapper<PlanResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectWrapper<PlanResponse> create(@RequestBody @Valid PlanCreateRequest request) {
        return service.create(request);
    }

    @Override
    @PutMapping(path = "/{id}", version = "v1+")
    public ObjectWrapper<PlanResponse> update(@PathVariable UUID id, @RequestBody @Valid PlanUpdateRequest request) {
        return service.update(id, request);
    }

    @Override
    @PatchMapping(path = "/{id}/status", version = "v1+")
    public ObjectWrapper<PlanResponse> changeStatus(@PathVariable UUID id, @RequestBody @Valid PlanStatusRequest request) {
        return service.changeStatus(id, request);
    }

    @Override
    @DeleteMapping(path = "/{id}", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
