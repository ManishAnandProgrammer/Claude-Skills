package com.example.app.user.repository;

import com.example.app.user.domain.Role;
import com.example.app.user.domain.TenantMembership;
import com.example.app.user.repository.custom.CustomTenantMembershipRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID>, CustomTenantMembershipRepository {

    Optional<TenantMembership> findByUserIdAndTenantId(UUID userId, UUID tenantId);

    boolean existsByUserIdAndTenantId(UUID userId, UUID tenantId);

    /** The user is fetched with it, in the same statement: a member's email and name are on the account. */
    @EntityGraph(attributePaths = "user")
    Optional<TenantMembership> findWithUserByUserIdAndTenantId(UUID userId, UUID tenantId);

    /** One tenant's memberships among {@code userIds}, their users fetched in the same statement. */
    @EntityGraph(attributePaths = "user")
    List<TenantMembership> findWithUserByTenantIdAndUserIdIn(UUID tenantId, Collection<UUID> userIds);

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

    /**
     * Stamps a token issue on the membership. A machine-written stamp, not an edit (DATA-24's exception):
     * it sets neither {@code version} nor {@code updated_at}, so the version an ADMIN sends back on a role
     * change stays current through the member's sign-ins, and two token issues at once never conflict. It
     * clears the persistence context, so a membership read before it is detached (its fields stay readable).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TenantMembership m set m.lastUsedAt = :now where m.id = :id")
    int markUsed(UUID id, Instant now);
}
