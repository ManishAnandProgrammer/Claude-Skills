package com.example.app.tenant.repository;

import com.example.app.tenant.domain.TenantMigration;
import com.example.app.tenant.domain.TenantMigrationStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantMigrationRepository extends JpaRepository<TenantMigration, UUID> {

    Optional<TenantMigration> findByTenantId(UUID tenantId);

    boolean existsByTenantId(UUID tenantId);

    /** Least recently tried first, so a tenant that keeps failing doesn't hold back the ones queued after it. */
    @Query("select m.tenantId from TenantMigration m where m.status = :status order by m.updatedAt, m.id")
    List<UUID> findTenantIdsByStatus(TenantMigrationStatus status, Limit limit);

    @Query("select m.tenantId from TenantMigration m where m.status in :statuses order by m.id")
    List<UUID> findTenantIdsByStatusIn(Collection<TenantMigrationStatus> statuses);
}
