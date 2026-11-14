package com.example.app.tenant.service;

import com.example.app.common.security.CurrentUser;
import com.example.app.tenant.api.PlatformAudit;
import com.example.app.tenant.domain.PlatformAuditEntry;
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

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID tenantId, String action, String detail) {
        repository.save(PlatformAuditEntry.of(currentUser.id(), tenantId, action, detail));
    }
}
