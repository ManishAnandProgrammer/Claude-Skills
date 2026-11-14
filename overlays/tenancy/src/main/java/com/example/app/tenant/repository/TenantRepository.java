package com.example.app.tenant.repository;

import com.example.app.tenant.api.TenantStatus;
import com.example.app.tenant.domain.Tenant;
import com.example.app.tenant.repository.custom.CustomTenantRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID>, CustomTenantRepository {

    /** Ids only, oldest first (UUIDv7): the jobs' loop over the tenants. */
    @Query("select t.id from Tenant t where t.status = :status order by t.id")
    List<UUID> findIdsByStatus(TenantStatus status);
}
