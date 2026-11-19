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

    /** Ids only, oldest first (UUIDv7): the jobs' loop over the tenants. */
    @Query("select t.id from Tenant t where t.status = :status order by t.id")
    List<UUID> findIdsByStatus(TenantStatus status);

    /** Like {@link #findIdsByStatus}, for several statuses. */
    @Query("select t.id from Tenant t where t.status in :statuses order by t.id")
    List<UUID> findIdsByStatusIn(Collection<TenantStatus> statuses);

    /**
     *   <!-- roles:begin -->
     * Locked for the rules over the tenant's members and their access that no constraint holds (DATA-29, house-review
     * AUTHZ-12 rule 7): every such change takes it first, so the caller's access and who manages the tenant are read
     * after any change that committed before.
     *   <!-- roles:else -->
     <!-- * Locked for two rules over the tenant's members that no constraint holds (DATA-29): only a current -->
     <!-- * ADMIN changes them, and the tenant keeps at least one ADMIN. -->
     *   <!-- roles:end -->
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Tenant> findForUpdateById(UUID id);
}
