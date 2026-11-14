package com.example.app.tenant.repository.custom;

import com.example.app.common.pagination.CursorBatch;
import com.example.app.tenant.dto.request.PlatformAuditFilter;
import com.example.app.tenant.dto.response.PlatformAuditResponse;

import java.util.UUID;

public interface CustomPlatformAuditEntryRepository {

    CursorBatch<PlatformAuditResponse> findByTenant(UUID tenantId, PlatformAuditFilter filter, int size);
}
