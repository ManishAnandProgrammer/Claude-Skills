package com.example.app.user.domain;

import com.example.app.common.security.Permission;
// tenancy-row:begin
import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
// import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OptimisticLock;

import java.util.EnumSet;
import java.util.HashSet;
// tenancy-row:begin
import java.util.Locale;
// tenancy-row:end
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A role an admin edits (the roles piece, Spring's group): a code, a name and the permissions it gives. A built-in
 * one ({@link #getBuiltIn()}, the {@link Role} constant it was seeded from) is a copy: its edits change this row,
 * never the enum. Roles only allow (no role-level DENY); a person's DENY is a personal grant. Its permissions are
 * members of this aggregate ({@link RolePermission}), written only here; changing them bumps the version, which an
 * edit sends back.
 */
@Entity
@Table(name = "roles")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
public class AccessRole extends TenantOwnedEntity {
// tenancy-row:else
// public class AccessRole extends AuditableEntity {
// tenancy-row:end

    /** Stable, the client's and the changesets' handle (a new permission's grant changeset names it). */
    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    /** The {@link Role} constant this built-in role was seeded from; null for a role an admin created. */
    @Column(name = "built_in", updatable = false)
    private String builtIn;

    @Getter(AccessLevel.NONE)
    @Builder.Default
    @OptimisticLock(excluded = false)
    @OneToMany(mappedBy = "role", cascade = {CascadeType.PERSIST, CascadeType.REMOVE}, orphanRemoval = true)
    private Set<RolePermission> permissionLinks = new HashSet<>();

    /** A new role; the caller has checked who may give these permissions and that each one's prerequisites are in. */
    public static AccessRole create(String code, String name, Set<Permission> permissions) {
        AccessRole role = AccessRole.builder().code(code).name(name.strip()).build();
        role.replacePermissions(permissions);
        return role;
    }

    // tenancy-row:begin
    /**
     * A copy of a built-in role as the {@link Role} enum lists it now, for a new tenant (row mode's activation): its
     * code and {@code built_in} the constant's name, its name the constant's in words ({@code ADMIN}: "Admin"), as
     * the roles changeset seeds them.
     */
    public static AccessRole copyOf(Role builtIn) {
        String code = builtIn.name();
        String name = code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        AccessRole role = AccessRole.builder().code(code).name(name).builtIn(code).build();
        role.replacePermissions(builtIn.permissions());
        return role;
    }

    // tenancy-row:end
    public void rename(String newName) {
        name = newName.strip();
    }

    /** Makes {@code replacement} its permissions: those it already has stay as they are. */
    public void replacePermissions(Set<Permission> replacement) {
        Set<String> wanted = replacement.stream().map(Permission::name).collect(Collectors.toSet());
        permissionLinks.removeIf(link -> !wanted.contains(link.getPermission()));
        Set<String> held = permissionCodes();
        for (String code : wanted) {
            if (!held.contains(code)) {
                permissionLinks.add(RolePermission.builder().role(this).permission(code).build());
            }
        }
    }

    /** The codes its rows hold, an unknown one included. */
    public Set<String> permissionCodes() {
        return permissionLinks.stream().map(RolePermission::getPermission).collect(Collectors.toSet());
    }

    /** The permissions it gives: its codes that name a {@link Permission} (an unknown code grants nothing). */
    public Set<Permission> permissions() {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String code : permissionCodes()) {
            Optional<Permission> known = PermissionCodes.known(code);
            known.ifPresent(permissions::add);
        }
        return permissions;
    }

    public boolean isBuiltIn() {
        return Objects.nonNull(builtIn);
    }
}
