package com.example.app.user.repository;

// roles:begin
// roles:else
// import com.example.app.user.domain.Role;
// roles:end
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

    @EntityGraph(attributePaths = "user")
    Optional<TenantMembership> findWithUserByUserIdAndTenantId(UUID userId, UUID tenantId);

    @EntityGraph(attributePaths = "user")
    List<TenantMembership> findWithUserByTenantIdAndUserIdIn(UUID tenantId, Collection<UUID> userIds);

    @Query("""
            select m from TenantMembership m
             where m.user.id = :userId
             order by m.lastUsedAt desc nulls last, m.id""")
    List<TenantMembership> findByUserIdMostRecentlyUsedFirst(UUID userId);

    // roles:begin
    Optional<TenantMembership> findFirstByTenantIdOrderById(UUID tenantId);

    Optional<TenantMembership> findFirstByTenantIdAndUserIdInOrderById(UUID tenantId, Collection<UUID> userIds);

    /** In id order: the order their locks are taken in. */
    @Query("""
            select m.tenantId from TenantMembership m
             where m.user.id = :userId
             order by m.tenantId""")
    List<UUID> findTenantIdsByUserId(UUID userId);

    // roles:else
    // /** The user is fetched with it: the token is built outside this read's transaction. */
    // @EntityGraph(attributePaths = "user")
    // Optional<TenantMembership> findFirstByTenantIdAndRoleOrderById(UUID tenantId, Role role);
    //
    // long countByTenantIdAndRole(UUID tenantId, Role role);
    //
    // /** In id order: the order their locks are taken in. */
    // @Query("""
    //         select m.tenantId from TenantMembership m
    //          where m.user.id = :userId and m.role = com.example.app.user.domain.Role.ADMIN
    //          order by m.tenantId""")
    // List<UUID> findTenantIdsWhereAdmin(UUID userId);
    //
    // @Query("""
    //         select count(m) from TenantMembership m
    //          where m.tenantId = :tenantId and m.role = com.example.app.user.domain.Role.ADMIN and m.user.id <> :userId
    //            and m.user.status = com.example.app.user.domain.UserStatus.ACTIVE""")
    // long countOtherActiveAdmins(UUID tenantId, UUID userId);
    //
    // roles:end
    /**
     * A machine-written stamp (house-review DATA-24's exception): neither {@code version} nor {@code updated_at}, so
     * two token issues at once never conflict. It clears the persistence context: a membership read before it is
     * detached (its fields stay readable).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TenantMembership m set m.lastUsedAt = :now where m.id = :id")
    int markUsed(UUID id, Instant now);
    // session-version:begin

    @Query("select m.sessionVersion from TenantMembership m where m.id = :id")
    Optional<Integer> findSessionVersion(UUID id);

    /**
     * A machine-written stamp (house-review DATA-24's exception): the number alone, neither {@code version} nor
     * {@code updated_at}. No clear: the entity maps the column read-only, so the caller's entities stay attached.
     */
    @Modifying(flushAutomatically = true)
    @Query("update TenantMembership m set m.sessionVersion = m.sessionVersion + 1 where m.id = :id")
    int bumpSessionVersion(UUID id);

    @Modifying(flushAutomatically = true)
    @Query("update TenantMembership m set m.sessionVersion = m.sessionVersion + 1 where m.user.id = :userId")
    int bumpSessionVersionsOfUser(UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("update TenantMembership m set m.sessionVersion = m.sessionVersion + 1 where m.tenantId = :tenantId")
    int bumpSessionVersionsOfTenant(UUID tenantId);

    @Modifying(flushAutomatically = true)
    @Query("update TenantMembership m set m.sessionVersion = m.sessionVersion + 1 where m.id in :ids")
    int bumpSessionVersions(Collection<UUID> ids);

    /** A superset of the pairs asked for: the caller keeps the pairs it wants. */
    List<TenantMembership> findByTenantIdInAndUserIdIn(Collection<UUID> tenantIds, Collection<UUID> userIds);

    @Query("""
            select new com.example.app.user.dto.internal.MembershipSessionVersion(m.id, m.sessionVersion)
              from TenantMembership m
             where m.user.id = :userId""")
    List<MembershipSessionVersion> findSessionVersionsOfUser(UUID userId);

    @Query("""
            select new com.example.app.user.dto.internal.MembershipSessionVersion(m.id, m.sessionVersion)
              from TenantMembership m
             where m.tenantId = :tenantId""")
    List<MembershipSessionVersion> findSessionVersionsOfTenant(UUID tenantId);

    @Query("""
            select new com.example.app.user.dto.internal.MembershipSessionVersion(m.id, m.sessionVersion)
              from TenantMembership m
             where m.id in :ids""")
    List<MembershipSessionVersion> findSessionVersions(Collection<UUID> ids);
    // session-version:end
}
