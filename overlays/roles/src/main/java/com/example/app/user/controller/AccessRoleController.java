package com.example.app.user.controller;

import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Requires;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.user.controller.api.AccessRoleApi;
import com.example.app.user.dto.request.AccessRoleCreateRequest;
import com.example.app.user.dto.request.AccessRoleFilter;
import com.example.app.user.dto.request.AccessRoleUpdateRequest;
import com.example.app.user.dto.response.AccessRoleResponse;
import com.example.app.user.dto.response.AccessRoleSummaryResponse;
import com.example.app.user.service.AccessRoleManager;
import com.example.app.user.service.AccessRoleService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/{version}/roles")
@RequiredArgsConstructor
class AccessRoleController implements AccessRoleApi {

    private final AccessRoleService service;
    private final AccessRoleManager manager;
    private final CurrentUser currentUser;

    @Override
    @GetMapping(version = "v1+")
    @Requires(Permission.ROLES_MANAGE)
    public PaginationWrapper<AccessRoleSummaryResponse> all(@Valid AccessRoleFilter filter) {
        return service.findByFilter(filter);
    }

    @Override
    @GetMapping(path = "/{id}", version = "v1+")
    @Requires(Permission.ROLES_MANAGE)
    public ObjectWrapper<AccessRoleResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Permission.ROLES_MANAGE)
    public ObjectWrapper<AccessRoleResponse> create(@RequestBody @Valid AccessRoleCreateRequest request) {
        return manager.create(currentUser.id(), request);
    }

    @Override
    @PutMapping(path = "/{id}", version = "v1+")
    @Requires(Permission.ROLES_MANAGE)
    public ObjectWrapper<AccessRoleResponse> update(@PathVariable UUID id, @RequestBody @Valid AccessRoleUpdateRequest request) {
        return manager.update(currentUser.id(), id, request);
    }

    @Override
    @DeleteMapping(path = "/{id}", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires(Permission.ROLES_MANAGE)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
