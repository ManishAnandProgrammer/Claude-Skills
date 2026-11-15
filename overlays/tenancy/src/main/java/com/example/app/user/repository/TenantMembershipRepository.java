package com.example.app.user.repository;

import com.example.app.user.domain.Role;
import com.example.app.user.domain.TenantMembership;
// session-version:begin
import com.example.app.user.dto.internal.MembershipSessionVersion;
// session-version:end
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

    /** The tenants where the user is an ADMIN, in id order (the order their locks are taken in). */
    @Query("""
            select m.tenantId from TenantMembership m
             where m.user.id = :userId and m.role = com.example.app.user.domain.Role.ADMIN
             order by m.tenantId""")
    List<UUID> findTenantIdsWhereAdmin(UUID userId);

    /** The tenant's ADMINs besides {@code userId} whose account is ACTIVE: the ones who could still manage it. */
    @Query("""
            select count(m) from TenantMembership m
             where m.tenantId = :tenantId and m.role = com.example.app.user.domain.Role.ADMIN and m.user.id <> :userId
               and m.user.status = com.example.app.user.domain.UserStatus.ACTIVE""")
    long countOtherActiveAdmins(UUID tenantId, UUID userId);

    /**
     * Stamps a token issue on the membership. A machine-written stamp, not an edit (DATA-24's exception):
     * it sets neither {@code version} nor {@code updated_at}, so the version an ADMIN sends back on a role
     * change stays current through the member's sign-ins, and two token issues at once never conflict. It
     * clears the persistence context, so a membership read before it is detached (its fields stay readable).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TenantMembership m set m.lastUsedAt = :now where m.id = :id")
    int markUsed(UUID id, Instant now);
    // session-version:begin

    /** The membership's session version, by primary key; empty once the membership is removed. */
    @Query("select m.sessionVersion from TenantMembership m where m.id = :id")
    Optional<Integer> findSessionVersion(UUID id);

    /**
     * The bumps below raise session versions in the transaction of the access change that needs them. A
     * machine-written stamp (DATA-24's exception): the number alone, neither {@code version} nor
     * {@code updated_at}, so the version an ADMIN sends back stays current. No clear: the entity maps the
     * column read-only, so a copy loaded earlier can't write its older number back, and the caller's
     * entities stay attached.
     */
    @Modifying(flushAutomatically = true)
    @Query("update TenantMembership m set m.sessionVersion = m.sessionVersion + 1 where m.id = :id")
    int bumpSessionVersion(UUID id);

    /** Every membership of the user (signing out everywhere). */
    @Modifying(flushAutomatically = true)
    @Query("update TenantMembership m set m.sessionVersion = m.sessionVersion + 1 where m.user.id = :userId")
    int bumpSessionVersionsOfUser(UUID userId);

    /** Every membership of the tenant (it was suspended), in one statement. */
    @Modifying(flushAutomatically = true)
    @Query("update TenantMembership m set m.sessionVersion = m.sessionVersion + 1 where m.tenantId = :tenantId")
    int bumpSessionVersionsOfTenant(UUID tenantId);

    /** The user's memberships' session versions as stored now (after a bump: read from the database, not the context). */
    @Query("""
            select new com.example.app.user.dto.internal.MembershipSessionVersion(m.id, m.sessionVersion)
              from TenantMembership m
             where m.user.id = :userId""")
    List<MembershipSessionVersion> findSessionVersionsOfUser(UUID userId);

    /** The tenant's memberships' session versions as stored now ({@code idx_tenant_memberships_tenant_id_id}). */
    @Query("""
            select new com.example.app.user.dto.internal.MembershipSessionVersion(m.id, m.sessionVersion)
              from TenantMembership m
             where m.tenantId = :tenantId""")
    List<MembershipSessionVersion> findSessionVersionsOfTenant(UUID tenantId);
    // session-version:end
}
