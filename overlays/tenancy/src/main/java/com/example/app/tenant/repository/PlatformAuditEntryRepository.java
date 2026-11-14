package com.example.app.tenant.repository;

import com.example.app.tenant.domain.PlatformAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlatformAuditEntryRepository extends JpaRepository<PlatformAuditEntry, UUID> {
}
