package com.example.app.user.repository;

import com.example.app.user.domain.Role;
import com.example.app.user.domain.TenantMembership;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    Optional<TenantMembership> findByUserIdAndTenantId(UUID userId, UUID tenantId);

    /** A user's memberships in login's order: the most recently used first, then the oldest. */
    @Query("""
            select m from TenantMembership m
             where m.user.id = :userId
             order by m.lastUsedAt desc nulls last, m.id""")
    List<TenantMembership> findByUserIdMostRecentlyUsedFirst(UUID userId);

    /** The user is fetched with it: the token is built outside this read's transaction. */
    @EntityGraph(attributePaths = "user")
    Optional<TenantMembership> findFirstByTenantIdAndRoleOrderById(UUID tenantId, Role role);

    long countByTenantIdAndRole(UUID tenantId, Role role);
}
