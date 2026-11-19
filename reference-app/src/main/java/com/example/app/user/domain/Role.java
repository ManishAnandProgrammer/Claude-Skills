package com.example.app.user.domain;

import com.example.app.common.security.Permission;

import java.util.Set;

/**
 *   <!-- roles:begin -->
 <!-- * A built-in role (the roles piece): each constant was seeded into the {@code roles} table with its permissions, -->
 <!-- * and that copy is the one people hold. Every assignment is {@code PUT /users/{userId}/roles/{roleId}}'s, but -->
 <!-- * the first ones: self-signup gives the built-in {@code USER}. -->
 <!-- *   <!-- tenancy:begin --> -->
 <!-- * With tenancy the roles are each tenant's: joining a tenant gives its built-in {@code USER}, and a tenant's -->
 <!-- * owner gets its built-in {@code ADMIN}. -->
 <!-- *   <!-- tenancy:end --> -->
 *   <!-- roles:else -->
 * A user's role, sent in the access token's {@code roles} claim.
 *   <!-- tenancy:begin -->
 * With tenancy it is the person's role in one tenant, their membership's ({@code GET /users/me/tenants}
 * lists them; {@code /users/me} has none). A tenant's owner becomes its first ADMIN; after
 * that a tenant ADMIN adds members with a role ({@code POST /tenant-members}) and changes it
 * ({@code PUT /tenant-members/{userId}}). A change reaches the member's next sign-in or refresh at the
 * latest.
 *   <!-- session-version:begin -->
 * It also raises the membership's session version, so the member's next request with an older token is
 * refused (401 {@code SESSION_VERSION_STALE}) and the refresh that follows brings the new role.
 *   <!-- session-version:end -->
 *   <!-- tenancy:else -->
 * Self-signup creates {@code USER}; further roles are added with the first feature that needs them.
 *   <!-- tenancy:end -->
 *   <!-- roles:end -->
 * Each role's javadoc names what it writes (on every role, when every role writes a resource); check it
 * is still true before adding to it.
 *
 *   <!-- roles:begin -->
 <!-- * <p>With the roles piece these constants are the built-in roles: changeset 0037 copied each one's permissions -->
 <!-- * into the {@code roles} table once, as literal rows, and from then on the table is what grants (an admin's edit -->
 <!-- * changes the copy, never this enum). A person may hold several roles and personal grants; the security chain -->
 <!-- * reads them on every request ({@code RolePermissions}), so a change reaches the person's next request. A new -->
 <!-- * permission here comes with a changeset granting it to the built-in roles that should hold it -->
 <!-- * ({@code BuiltInRolesIntegrationTest} compares the two on a fresh database).</p> -->
 *   <!-- roles:else -->
 * <p>A role is Spring's group: it lists its {@link Permission}s here, in code, and the security chain turns
 * the token's role into exactly these authorities ({@code RolePermissions}); a handler checks a permission,
 * never a role. Roles don't include each other: a permission two roles share is listed on both, and each role
 * holds the prerequisites of everything it holds ({@code RoleTest}). The role (and so its permissions) is
 * the token's: a change reaches the person within one access-token lifetime, as said above.</p>
 *   <!-- roles:end -->
 */
public enum Role {
    // example:begin
    /** Works their own todo lists, todos, tags and plans. */
    // example:end
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
    // tenancy:begin
    // /** Everything a USER works, plus the tenant's members (who belongs, with which role) and its own settings. */
    // tenancy:else
    /**
     * Finds other accounts ({@code GET /users?email=}), suspends and reactivates them
     * ({@code PATCH /users/{id}/status}), and changes
     * application-wide settings: configuration metadata, upload policies.
     *   <!-- roles:begin -->
     <!-- * With the roles piece it also manages roles and people's access; the first ADMIN is an operator's -->
     <!-- * assignment (docs/runbook.md, Operator tasks), every later one {@code PUT /users/{userId}/roles/{roleId}}'s. -->
     *   <!-- roles:else -->
     * No endpoint grants
     * it: an operator sets it in the database ({@code UPDATE users SET role = 'ADMIN'}; docs/runbook.md,
     * Operator tasks), and it reaches the user's next sign-in or refresh at the latest.
     *   <!-- session-version:begin -->
     * The runbook's statement also raises the account's session version and drops its copy in Redis
     * ({@code DEL sv:<id>}), so the user's next request with an older token is refused (401
     * {@code SESSION_VERSION_STALE}) and the refresh that follows brings the new role; without the
     * {@code DEL}, within {@code app.session-version.cache-ttl} (1 minute).
     *   <!-- session-version:end -->
     *   <!-- roles:end -->
     */
    // tenancy:end
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

    /** Exactly what this role may do; every one of them becomes an authority of its tokens. */
    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean grants(Permission permission) {
        return permissions.contains(permission);
    }
}
