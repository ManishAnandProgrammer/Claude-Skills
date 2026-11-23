package com.example.app.tenant.service;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.tenant.dto.request.TenantCreateRequest;
import com.example.app.tenant.dto.response.TenantResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Row mode provisions right after the approving transaction commits: a body mapped inside that transaction would show
 * an ACTIVE tenant as PENDING, with the version before its activation, so the read starts after the commit.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class StaffTenantService {

    private final TenantService tenants;

    public ObjectWrapper<TenantResponse> create(TenantCreateRequest request) {
        UUID tenantId = tenants.create(request);
        return tenants.findById(tenantId);
    }

    public ObjectWrapper<TenantResponse> approve(UUID tenantId) {
        tenants.approve(tenantId);
        return tenants.findById(tenantId);
    }
}
