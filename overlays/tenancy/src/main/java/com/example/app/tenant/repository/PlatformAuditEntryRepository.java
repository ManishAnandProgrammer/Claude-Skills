package com.example.app.tenant.repository;

// session-version:begin
import com.example.app.tenant.api.ImpersonatedMember;
// session-version:end
import com.example.app.tenant.domain.PlatformAuditEntry;
import com.example.app.tenant.repository.custom.CustomPlatformAuditEntryRepository;
import org.springframework.data.jpa.repository.JpaRepository;
// session-version:begin
import org.springframework.data.jpa.repository.Query;
// session-version:end

// session-version:begin
import java.time.Instant;
import java.util.List;
// session-version:end
import java.util.UUID;

public interface PlatformAuditEntryRepository extends JpaRepository<PlatformAuditEntry, UUID>, CustomPlatformAuditEntryRepository {
    // session-version:begin

    @Query("""
            select distinct new com.example.app.tenant.api.ImpersonatedMember(e.tenantId, e.impersonatedUserId)
              from PlatformAuditEntry e
             where e.actorId = :staffId and e.impersonatedUserId is not null and e.createdAt >= :since""")
    List<ImpersonatedMember> findImpersonatedBy(UUID staffId, Instant since);
    // session-version:end
}
