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

@Entity
@Table(name = "tenant_memberships")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantMembership extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    // roles:begin
    // roles:else
    // @Enumerated(EnumType.STRING)
    // @Column(nullable = false)
    // private Role role;
    //
    // roles:end
    @Column(name = "last_used_at")
    private Instant lastUsedAt;
    // session-version:begin

    /**
     * Read-only: only {@code TenantMembershipRepository}'s bumps write it, so an entity loaded before a bump can never
     * write its older number back.
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
    // public void changeRole(Role newRole) {
    //     role = newRole;
    // }
    // roles:end
}
