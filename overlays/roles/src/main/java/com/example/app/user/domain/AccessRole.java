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

    /** A new permission's grant changeset names the role by it. */
    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "built_in", updatable = false)
    private String builtIn;

    /** Changing them bumps the role's version, which an edit sends back. */
    @Getter(AccessLevel.NONE)
    @Builder.Default
    @OptimisticLock(excluded = false)
    @OneToMany(mappedBy = "role", cascade = {CascadeType.PERSIST, CascadeType.REMOVE}, orphanRemoval = true)
    private Set<RolePermission> permissionLinks = new HashSet<>();

    /** The caller has checked who may give these permissions and that each one's prerequisites are in. */
    public static AccessRole create(String code, String name, Set<Permission> permissions) {
        AccessRole role = AccessRole.builder().code(code).name(name.strip()).build();
        role.replacePermissions(permissions);
        return role;
    }

    // tenancy-row:begin
    /**
     * As the roles changeset seeds a built-in role: code and {@code built_in} the constant's name, {@code ADMIN}
     * named "Admin".
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

    public Set<String> permissionCodes() {
        return permissionLinks.stream().map(RolePermission::getPermission).collect(Collectors.toSet());
    }

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
