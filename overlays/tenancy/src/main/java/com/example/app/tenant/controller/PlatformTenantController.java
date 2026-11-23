package com.example.app.tenant.controller;

import com.example.app.common.security.SignedIn;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.tenant.controller.api.PlatformTenantApi;
import com.example.app.tenant.dto.request.PlatformAuditFilter;
import com.example.app.tenant.dto.request.TenantCreateRequest;
import com.example.app.tenant.dto.request.TenantFilter;
import com.example.app.tenant.dto.request.TenantStatusRequest;
import com.example.app.tenant.dto.response.PlatformAuditResponse;
import com.example.app.tenant.dto.response.TenantResponse;
import com.example.app.tenant.service.PlatformAuditService;
import com.example.app.tenant.service.StaffTenantService;
import com.example.app.tenant.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/{version}/platform/tenants")
@RequiredArgsConstructor
class PlatformTenantController implements PlatformTenantApi {

    private final TenantService service;
    private final StaffTenantService staffTenants;
    private final PlatformAuditService audit;

    @Override
    @GetMapping(version = "v1+")
    @SignedIn
    public PaginationWrapper<TenantResponse> all(@Valid TenantFilter filter) {
        return service.findByFilter(filter);
    }

    @Override
    @GetMapping(path = "/{id}", version = "v1+")
    @SignedIn
    public ObjectWrapper<TenantResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    @SignedIn
    public ObjectWrapper<TenantResponse> create(@RequestBody @Valid TenantCreateRequest request) {
        return staffTenants.create(request);
    }

    @Override
    @PostMapping(path = "/{id}/approve", version = "v1+")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SignedIn
    public ObjectWrapper<TenantResponse> approve(@PathVariable UUID id) {
        return staffTenants.approve(id);
    }

    @Override
    @PatchMapping(path = "/{id}/status", version = "v1+")
    @SignedIn
    public ObjectWrapper<TenantResponse> changeStatus(@PathVariable UUID id, @RequestBody @Valid TenantStatusRequest request) {
        return service.changeStatus(id, request);
    }

    @Override
    @GetMapping(path = "/{id}/audit", version = "v1+")
    @SignedIn
    public CursorWrapper<PlatformAuditResponse> audit(@PathVariable UUID id, @Valid PlatformAuditFilter filter) {
        return audit.findByTenant(id, filter);
    }
}
