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

/**
 * A direct cross-tenant edit by platform staff (owner decision O6), the pattern for any other: the
 * tenant is named in the platform path, the edit is audited, and the tenant's own service runs inside
 * that tenant's scope, so it reads and writes that tenant's tables only, with its usual rules. No
 * transaction here: the scope must be set before the service's transaction opens.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PlatformMetadataService {

    static final String AUDIT_ACTION = "METADATA_UPDATE";

    private final MetadataService metadata;
    private final Tenants tenants;
    private final PlatformAudit audit;

    /** As the tenant's admin would, through {@link MetadataService#updateByName}; recorded first, so a refused edit is on record too. */
    public ObjectWrapper<MetadataResponse> updateByName(UUID tenantId, String name, MetadataUpdateRequest request) {
        tenants.requireActive(tenantId);
        audit.record(tenantId, AUDIT_ACTION, name);
        return TenantScope.callAs(tenantId, () -> metadata.updateByName(name, request));
    }
}
