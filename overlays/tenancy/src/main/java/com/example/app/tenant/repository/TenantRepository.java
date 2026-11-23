package com.example.app.tenant.repository;

import com.example.app.tenant.api.TenantStatus;
import com.example.app.tenant.domain.Tenant;
import com.example.app.tenant.repository.custom.CustomTenantRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID>, CustomTenantRepository {

    @Query("select t.id from Tenant t where t.status = :status order by t.id")
    List<UUID> findIdsByStatus(TenantStatus status);

    @Query("select t.id from Tenant t where t.status in :statuses order by t.id")
    List<UUID> findIdsByStatusIn(Collection<TenantStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Tenant> findForUpdateById(UUID id);
}
