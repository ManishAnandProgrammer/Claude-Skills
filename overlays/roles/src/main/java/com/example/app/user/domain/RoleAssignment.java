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

/** With the person's {@link PersonalGrant}s, written only by {@code PersonAccessService}, under its access-change lock. */
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

    public void changeValidUntil(Instant newValidUntil) {
        validUntil = newValidUntil;
    }
}
