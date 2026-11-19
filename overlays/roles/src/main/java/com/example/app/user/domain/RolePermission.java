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

/**
 * One permission of one role, a member of the role's aggregate: written only through {@link AccessRole}. The code
 * is text the application checks ({@link PermissionCodes}); a row is inserted and deleted, never updated.
 */
@Entity
@Table(name = "role_permissions")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
public class RolePermission extends TenantOwnedEntity {
// tenancy-row:else
// public class RolePermission extends AuditableEntity {
// tenancy-row:end

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false, updatable = false)
    private AccessRole role;

    @Column(nullable = false, updatable = false)
    private String permission;
}
