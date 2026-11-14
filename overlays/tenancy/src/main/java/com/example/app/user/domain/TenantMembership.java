package com.example.app.user.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A user's place in one tenant (the tenancy piece): accounts are global, the role lives here, so the
 * same person can be ADMIN in one tenant and USER in another. An access token is for one membership.
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** When tokens were last issued for it: login picks the most recent. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public static TenantMembership of(User user, UUID tenantId, Role role) {
        return TenantMembership.builder()
                .user(user)
                .tenantId(tenantId)
                .role(role)
                .build();
    }

    /** A tenant ADMIN's change; the service keeps the tenant's last ADMIN. */
    public void changeRole(Role newRole) {
        role = newRole;
    }

    public void markUsed(Instant now) {
        lastUsedAt = now;
    }
}
