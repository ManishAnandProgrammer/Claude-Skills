package com.example.app.tenant.service;

import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.tenant.api.PlatformAudit;
import com.example.app.tenant.domain.PlatformAuditEntry;
import com.example.app.tenant.dto.request.PlatformAuditFilter;
import com.example.app.tenant.dto.response.PlatformAuditResponse;
import com.example.app.tenant.repository.PlatformAuditEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** {@link PlatformAudit}: rows are only ever inserted. The actor is the platform token's subject. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformAuditService implements PlatformAudit {

    private final PlatformAuditEntryRepository repository;
    private final CurrentUser currentUser;
    private final TenantService tenants;
    private final Paging paging;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID tenantId, String action, String detail) {
        repository.save(PlatformAuditEntry.of(currentUser.id(), tenantId, action, detail));
    }

    /**
     * A tenant's support history for staff, newest first. 404 {@code TENANT_NOT_FOUND}. The batch is read first,
     * so its cursor is checked before the lookup (a tampered one is 400 whatever the tenant).
     */
    public CursorWrapper<PlatformAuditResponse> findByTenant(UUID tenantId, PlatformAuditFilter filter) {
        CursorBatch<PlatformAuditResponse> batch = repository.findByTenant(tenantId, filter, paging.cursorSize(filter));
        tenants.getTenant(tenantId);
        return CursorWrapper.of(batch.items(), batch.nextCursor());
    }
}
