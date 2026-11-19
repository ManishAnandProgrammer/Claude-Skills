package com.example.app.user.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
// roles:begin
// roles:else
// import jakarta.persistence.EnumType;
// import jakarta.persistence.Enumerated;
// roles:end
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 *   <!-- roles:begin -->
 * A user's place in one tenant (the tenancy piece): accounts are global, the person's access is the tenant's (the
 * roles piece: their roles and grants in the tenant's own tables, on its member row), so the same person can hold
 * the tenant's ADMIN role in one tenant and USER in another. An access token is for one membership.
 *   <!-- roles:else -->
 <!-- * A user's place in one tenant (the tenancy piece): accounts are global, the role lives here, so the -->
 <!-- * same person can be ADMIN in one tenant and USER in another. An access token is for one membership. -->
 *   <!-- roles:end -->
 */
@Entity
@Table(name = "tenant_memberships")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantMembership extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** The tenant module's record; an id, since entities stay inside their module. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    // roles:begin
    // roles:else
    // @Enumerated(EnumType.STRING)
    // @Column(nullable = false)
    // private Role role;
    //
    // roles:end
    /**
     * When tokens were last issued for it: login picks the most recent. A machine-written stamp, written
     * only by {@code TenantMembershipRepository.markUsed}, outside the membership's version.
     */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;
    // session-version:begin

    /**
     * The number every access token for this membership carries (claim {@code sv}); a token with another one
     * is refused. Read-only here: only {@code TenantMembershipRepository}'s bumps write it, so an entity loaded
     * before a bump can never write its older copy back, and a new membership starts at the column's default.
     */
    @Column(name = "session_version", insertable = false, updatable = false)
    private int sessionVersion;
    // session-version:end

    // roles:begin
    public static TenantMembership of(User user, UUID tenantId) {
        return TenantMembership.builder()
                .user(user)
                .tenantId(tenantId)
                .build();
    }
    // roles:else
    // public static TenantMembership of(User user, UUID tenantId, Role role) {
    //     return TenantMembership.builder()
    //             .user(user)
    //             .tenantId(tenantId)
    //             .role(role)
    //             .build();
    // }
    //
    // /** A tenant ADMIN's change; the service keeps the tenant's last ADMIN. */
    // public void changeRole(Role newRole) {
    //     role = newRole;
    // }
    // roles:end
}
