package com.example.app.user.domain;

// tenancy-row:begin
import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
// import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A person holding a role (the roles piece), for good or until {@link #getValidUntil()}: past it the assignment
 * grants nothing, and it stays until someone removes it. Together with the person's {@link PersonalGrant}s it is
 * one aggregate, a person's access, written by {@code PersonAccessService} under the lock of the person's row.
 *   <!-- tenancy:begin -->
 * With tenancy the person is a member of the tenant ({@code members.user_id}): the roles are the tenant's.
 *   <!-- tenancy:end -->
 */
@Entity
@Table(name = "user_roles")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
public class RoleAssignment extends TenantOwnedEntity {
// tenancy-row:else
// public class RoleAssignment extends AuditableEntity {
// tenancy-row:end

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false, updatable = false)
    private AccessRole role;

    @Column(name = "valid_until")
    private Instant validUntil;

    public static RoleAssignment of(UUID userId, AccessRole role, Instant validUntil) {
        return RoleAssignment.builder().userId(userId).role(role).validUntil(validUntil).build();
    }

    /** Another end, or none; the same one again changes nothing (no version move), so the request is idempotent. */
    public void changeValidUntil(Instant newValidUntil) {
        validUntil = newValidUntil;
    }
}
