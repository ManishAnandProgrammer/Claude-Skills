package com.example.app.user.domain;

import com.example.app.common.security.Permission;

import java.util.Set;

// roles:begin
// /**
//  * These constants only seed the built-in roles: changeset 0037 copied each one's permissions into the
//  * {@code roles} table once, and from then on the table grants, so a permission added here needs a changeset
//  * granting it too ({@code BuiltInRolesIntegrationTest} compares the two on a fresh database).
//  */
// roles:end
public enum Role {
    USER(new Permission[] {
            // example:begin
            Permission.TODO_LIST_READ_OWN, Permission.TODO_LIST_CREATE_OWN, Permission.TODO_LIST_EDIT_OWN,
            Permission.TODO_LIST_DELETE_OWN,
            Permission.TODO_READ_OWN, Permission.TODO_CREATE_OWN, Permission.TODO_EDIT_OWN, Permission.TODO_DELETE_OWN,
            Permission.TAG_READ_OWN, Permission.TAG_CREATE_OWN, Permission.TAG_EDIT_OWN, Permission.TAG_DELETE_OWN,
            Permission.PLAN_READ_OWN, Permission.PLAN_CREATE_OWN, Permission.PLAN_EDIT_OWN, Permission.PLAN_DELETE_OWN,
            // example:end
    }),
    // admin:begin (with the first piece that has admin endpoints: sign-in's account suspension, metadata, media)
    ADMIN(new Permission[] {
            // example:begin
            Permission.TODO_LIST_READ_OWN, Permission.TODO_LIST_CREATE_OWN, Permission.TODO_LIST_EDIT_OWN,
            Permission.TODO_LIST_DELETE_OWN,
            Permission.TODO_READ_OWN, Permission.TODO_CREATE_OWN, Permission.TODO_EDIT_OWN, Permission.TODO_DELETE_OWN,
            Permission.TAG_READ_OWN, Permission.TAG_CREATE_OWN, Permission.TAG_EDIT_OWN, Permission.TAG_DELETE_OWN,
            Permission.PLAN_READ_OWN, Permission.PLAN_CREATE_OWN, Permission.PLAN_EDIT_OWN, Permission.PLAN_DELETE_OWN,
            // example:end
            // tenancy:begin
            // Permission.TENANT_MEMBERS_MANAGE,
            // tenancy:else
            Permission.ACCOUNT_READ, Permission.ACCOUNT_STATUS_CHANGE,
            // tenancy:end
            // roles:begin
            // Permission.ROLES_MANAGE, Permission.ACCESS_GRANT,
            // roles:end
            // metadata:begin
            Permission.METADATA_MANAGE,
            // metadata:end
            // media:begin
            Permission.UPLOAD_POLICY_MANAGE,
            // media:end
    }),
    // admin:end
    ;

    private final Set<Permission> permissions;

    Role(Permission... permissions) {
        this.permissions = Set.of(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean grants(Permission permission) {
        return permissions.contains(permission);
    }
}
