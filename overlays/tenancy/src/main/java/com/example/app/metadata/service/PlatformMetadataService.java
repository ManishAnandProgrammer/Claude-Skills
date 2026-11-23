package com.example.app.metadata.service;

import com.example.app.common.tenancy.TenantScope;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.metadata.dto.request.MetadataUpdateRequest;
import com.example.app.metadata.dto.response.MetadataResponse;
import com.example.app.tenant.api.PlatformAudit;
import com.example.app.tenant.api.Tenants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** No transaction here: the tenant's scope must be set before the tenant service's transaction opens. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PlatformMetadataService {

    static final String AUDIT_ACTION = "METADATA_UPDATE";

    private final MetadataService metadata;
    private final Tenants tenants;
    private final PlatformAudit audit;

    public ObjectWrapper<MetadataResponse> findByName(UUID tenantId, String name) {
        tenants.requireActive(tenantId);
        return TenantScope.callAs(tenantId, () -> metadata.findByName(name));
    }

    /** Recorded first, so a refused edit is on record too. */
    public ObjectWrapper<MetadataResponse> updateByName(UUID tenantId, String name, MetadataUpdateRequest request) {
        tenants.requireActive(tenantId);
        audit.record(tenantId, AUDIT_ACTION, name);
        return TenantScope.callAs(tenantId, () -> metadata.updateByName(name, request));
    }
}
