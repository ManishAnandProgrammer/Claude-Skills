package com.example.app.user;

import com.example.app.common.security.Area;
import com.example.app.common.security.Permission;
import com.example.app.support.RolesFixtures;
import com.example.app.user.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roles an admin edits (the roles piece, house-review AUTHZ-12): the catalogue, the role's CRUD and the safety rules
 * about a role (1, 4, 5, 6, 7). A test that edits a built-in role puts it back, so the built-in roles' own test
 * ({@code BuiltInRolesIntegrationTest}) reads them as the changeset seeded them.
 */
class RolesIntegrationTest extends RolesFixtures {

    private static String roleUpdate(String name, long version, Permission... permissions) {
        return """
                {"name": "%s", "permissions": %s, "version": %d}""".formatted(name, permissionsJson(permissions), version);
    }

    private String builtInId(Role role) {
        return jdbc.queryForObject("select id::text from roles where built_in = ?", String.class, role.name());
    }

    private long versionOf(String roleId) {
        Long version = jdbc.queryForObject("select version from roles where id = ?::uuid", Long.class, roleId);
        return version;
    }

    @Test
    @DisplayName("The catalogue lists every permission with its area, reach and prerequisites, and every area")
    void theCatalogueListsEveryPermission() throws Exception {
        getAs(newUser().accessToken(), "/api/v1/permissions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.permissions.length()").value(Permission.values().length))
                .andExpect(jsonPath("$.response.areas.length()").value(Area.values().length))
                .andExpect(jsonPath("$.response.permissions[?(@.code == '%s')].prerequisites[0]".formatted(NEEDING))
                        .value(hasItem(NEEDED.name())))
                .andExpect(jsonPath("$.response.permissions[?(@.code == '%s')].reach".formatted(NEEDED)).value(hasItem("ALL")));
    }

    @Test
    @DisplayName("A role is created with its permissions, read back, listed by its code, and holds nobody until given")
    void aRoleIsCreatedAndRead() throws Exception {
        String admin = newAdmin().accessToken();
        String code = uniqueCode();

        String id = read(postAs(admin, ROLES, """
                {"code": "%s", "name": " Accountant ", "permissions": %s}
                """.formatted(code, permissionsJson(NEEDING, NEEDED))).andExpect(status().isCreated()), "$.response.id");

        getAs(admin, ROLES + "/" + id)
                .andExpect(jsonPath("$.response.code").value(code))
                .andExpect(jsonPath("$.response.name").value("Accountant"))
                .andExpect(jsonPath("$.response.builtIn").doesNotExist())
                .andExpect(jsonPath("$.response.permissions", contains(NEEDED.name(), NEEDING.name())));
        getAs(admin, ROLES + "?query=" + code.substring(2, 20))
                .andExpect(jsonPath("$.response[*].code", contains(code)))
                .andExpect(jsonPath("$.response[0].permissions").doesNotExist());
        postAs(admin, ROLES, """
                {"code": "%s", "name": "Again", "permissions": []}
                """.formatted(code)).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ROLE_CODE_TAKEN"));
    }

    @Test
    @DisplayName("Safety rule 1: a role holds only permissions its creator or editor holds (403 ACCESS_NOT_HELD)")
    void aRoleHoldsOnlyWhatItsEditorHolds() throws Exception {
        TestUser editor = newUserHolding(Permission.ROLES_MANAGE);

        postAs(editor.accessToken(), ROLES, """
                {"code": "%s", "name": "Too much", "permissions": %s}
                """.formatted(uniqueCode(), permissionsJson(NEEDED)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_NOT_HELD"));
        String own = read(postAs(editor.accessToken(), ROLES, """
                {"code": "%s", "name": "Enough", "permissions": %s}
                """.formatted(uniqueCode(), permissionsJson(Permission.ROLES_MANAGE))).andExpect(status().isCreated()), "$.response.id");
        putAs(editor.accessToken(), ROLES + "/" + own, roleUpdate("Enough", 0, Permission.ROLES_MANAGE, NEEDED))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_NOT_HELD"));
    }

    @Test
    @DisplayName("Safety rule 4: a role without a prerequisite of one of its permissions is 400 ROLE_PREREQUISITE_MISSING, nothing filled in")
    void aMissingPrerequisiteIsRefused() throws Exception {
        String admin = newAdmin().accessToken();

        postAs(admin, ROLES, """
                {"code": "%s", "name": "Half", "permissions": %s}
                """.formatted(uniqueCode(), permissionsJson(NEEDING)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ROLE_PREREQUISITE_MISSING"));
        String role = createRole(admin, NEEDED);
        putAs(admin, ROLES + "/" + role, roleUpdate("Half", 0, NEEDING))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ROLE_PREREQUISITE_MISSING"));
        getAs(admin, ROLES + "/" + role).andExpect(jsonPath("$.response.permissions", contains(NEEDED.name())));
    }

    @Test
    @DisplayName("Safety rule 5: a role is deleted only while nobody holds it (409 ROLE_IN_USE), and a built-in one never")
    void aHeldRoleIsNeverDeleted() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser holder = newUser();
        String role = createRole(admin, NEEDED);
        assign(admin, holder.id(), role, null).andExpect(status().isOk());

        deleteAs(admin, ROLES + "/" + role).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ROLE_IN_USE"));
        assertThat(myPermissions(holder.accessToken())).contains(NEEDED.name());

        deleteAs(admin, userRole(holder.id(), role)).andExpect(status().isNoContent());
        deleteAs(admin, ROLES + "/" + role).andExpect(status().isNoContent());
        getAs(admin, ROLES + "/" + role).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("ROLE_NOT_FOUND"));
        deleteAs(admin, ROLES + "/" + builtInId(Role.USER))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ROLE_BUILT_IN"));
    }

    // tenancy:begin
    // In a tenant of its own, where only its owner holds what manages it for good: TenantAccessIntegrationTest.
    // tenancy:else
    // @Test
    // @DisplayName("Safety rule 6: an edit that would leave no active account holding ROLES_MANAGE or ACCESS_GRANT for good is 409 ACCESS_LAST_MANAGER")
    // void theLastManagerIsKept() throws Exception {
    //     String admin = newAdmin().accessToken();
    //     // A holder of both until a date, so the edit would leave someone holding them, just not for good.
    //     newUserHolding(Permission.ROLES_MANAGE, Permission.ACCESS_GRANT, NEEDED);
    //     assertThat(jdbc.queryForObject("""
    //             select count(*) from user_roles a join role_permissions p on p.role_id = a.role_id join roles r on r.id = a.role_id
    //              where p.permission in ('ROLES_MANAGE', 'ACCESS_GRANT') and a.valid_until is null and r.built_in is distinct from 'ADMIN'""",
    //             Integer.class)).as("no holder for good but through the built-in ADMIN, which this test relies on").isZero();
    //     String adminRole = builtInId(Role.ADMIN);
    //     long version = versionOf(adminRole);
    //     Permission[] seeded = Role.ADMIN.permissions().toArray(Permission[]::new);
    //     try {
    //         for (Permission kept : List.of(Permission.ROLES_MANAGE, Permission.ACCESS_GRANT)) {
    //             Permission[] without = Role.ADMIN.permissions().stream().filter(permission -> permission != kept).toArray(Permission[]::new);
    //
    //             putAs(admin, ROLES + "/" + adminRole, roleUpdate("Admin", version, without))
    //                     .andExpect(status().isConflict())
    //                     .andExpect(jsonPath("$.errorCode").value("ACCESS_LAST_MANAGER"));
    //         }
    //         assertThat(versionOf(adminRole)).isEqualTo(version);
    //         assertThat(myPermissions(admin)).contains(Permission.ROLES_MANAGE.name(), Permission.ACCESS_GRANT.name());
    //     } finally {
    //         if (versionOf(adminRole) != version) {
    //             putAs(admin, ROLES + "/" + adminRole, roleUpdate("Admin", versionOf(adminRole), seeded));
    //         }
    //     }
    // }
    // tenancy:end
    @Test
    @DisplayName("Safety rule 1 on an edit: an editor never takes from a role a permission they don't hold (403 ACCESS_NOT_HELD)")
    void anEditorNeverStripsWhatTheyLack() throws Exception {
        String admin = newAdmin().accessToken();
        String role = createRole(admin, NEEDED);
        TestUser editor = newUserHolding(Permission.ROLES_MANAGE);

        putAs(editor.accessToken(), ROLES + "/" + role, roleUpdate("Stripped", 0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_NOT_HELD"));
        getAs(admin, ROLES + "/" + role).andExpect(jsonPath("$.response.permissions", contains(NEEDED.name())));
    }

    @Test
    @DisplayName("Safety rule 2 on an edit: adding to a role the editor holds is 403 ACCESS_OWN_CHANGE (their grant until a date would outlive it)")
    void anEditorNeverAddsToTheirOwnRole() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser editor = newUser();
        String editorsRole = createRole(admin, Permission.ROLES_MANAGE);
        assign(admin, editor.id(), editorsRole, LATER).andExpect(status().isOk());
        putAs(admin, userPermission(editor.id(), NEEDED), """
                {"effect": "ALLOW", "validUntil": "%s"}""".formatted(LATER)).andExpect(status().isOk());

        putAs(editor.accessToken(), ROLES + "/" + editorsRole, roleUpdate("Mine", 0, Permission.ROLES_MANAGE, NEEDED))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_OWN_CHANGE"));
        putAs(editor.accessToken(), ROLES + "/" + editorsRole, roleUpdate("Renamed", 0, Permission.ROLES_MANAGE))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Safety rule 7: an edit with the version you read; a stale one is 409 OPTIMISTIC_LOCK_CONFLICT")
    void aStaleEditIsRefused() throws Exception {
        String admin = newAdmin().accessToken();
        String role = createRole(admin, NEEDED);
        putAs(admin, ROLES + "/" + role, roleUpdate("Renamed", 0, NEEDED)).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1));

        putAs(admin, ROLES + "/" + role, roleUpdate("Again", 0, NEEDED))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
    }

    @Test
    @DisplayName("A built-in role is a copy: its edit changes what its holders hold on their next request, never the enum")
    void aBuiltInRoleIsACopy() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser user = newUserWithRole("USER");
        String userRole = builtInId(Role.USER);
        Set<Permission> seeded = Role.USER.permissions();
        Permission[] seededArray = seeded.toArray(Permission[]::new);
        Permission[] withNeeded = Arrays.copyOf(seededArray, seededArray.length + 1);
        withNeeded[seededArray.length] = NEEDED;

        try {
            putAs(admin, ROLES + "/" + userRole, roleUpdate("User", versionOf(userRole), withNeeded)).andExpect(status().isOk());

            assertThat(myPermissions(user.accessToken())).contains(NEEDED.name());
            assertThat(Role.USER.permissions()).as("the enum stays as it was").doesNotContain(NEEDED);
        } finally {
            putAs(admin, ROLES + "/" + userRole, roleUpdate("User", versionOf(userRole), seededArray)).andExpect(status().isOk());
        }
        assertThat(myPermissions(user.accessToken())).doesNotContain(NEEDED.name());
    }

    @Test
    @DisplayName("Removing a permission from a role takes it from everyone who holds the role on their next request")
    void aRoleEditReachesItsHolders() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser holder = newUser();
        String role = createRole(admin, NEEDED, NEEDING);
        assign(admin, holder.id(), role, null).andExpect(status().isOk());
        assertThat(myPermissions(holder.accessToken())).contains(NEEDED.name(), NEEDING.name());

        putAs(admin, ROLES + "/" + role, roleUpdate("Fixture role", 0, NEEDED)).andExpect(status().isOk());

        List<String> after = myPermissions(holder.accessToken());
        assertThat(after).contains(NEEDED.name()).doesNotContain(NEEDING.name());
    }

    @Test
    @DisplayName("Only ROLES_MANAGE manages roles: a USER's list and delete are 403 ACCESS_DENIED")
    void onlyRoleManagersManageRoles() throws Exception {
        String user = newUser().accessToken();

        getAs(user, ROLES).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        deleteAs(user, ROLES + "/" + UUID.randomUUID()).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("""
            The roles dropdown lets someone who gives roles without managing them pick one (for=ACCESS_GRANT): only the \
            roles whose every permission they hold, by name, in one statement; 403 without for's permission, 400 for a \
            status roles don't have""")
    void theDropdownOffersTheRolesTheCallerMayGive() throws Exception {
        String admin = newAdmin().accessToken();
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String givable = namedRole(admin, "Dropdown " + marker + " b", NEEDED);
        String alsoGivable = namedRole(admin, "Dropdown " + marker + " a");
        namedRole(admin, "Dropdown " + marker + " c", NEEDED, Permission.ROLES_MANAGE);
        String granter = newUserHolding(Permission.ACCESS_GRANT, NEEDED).accessToken();
        String options = ROLES + "/dropdown?for=ACCESS_GRANT&query=" + marker;
        getAs(granter, ROLES).andExpect(status().isForbidden());

        getAs(granter, options)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].value", contains(alsoGivable, givable)))
                .andExpect(jsonPath("$.response[*].label", contains("Dropdown " + marker + " a", "Dropdown " + marker + " b")));
        statistics().clear();
        getAs(granter, options).andExpect(status().isOk());
        assertThat(statistics().getPrepareStatementCount()).as("the dropdown's statements").isEqualTo(1);

        getAs(newUser().accessToken(), options)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        getAs(granter, options + "&status=INACTIVE")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("status"));
    }

    /** A new role with this name and exactly these permissions; its id. */
    private String namedRole(String adminToken, String name, Permission... permissions) throws Exception {
        return read(postAs(adminToken, ROLES, """
                {"code": "%s", "name": "%s", "permissions": %s}
                """.formatted(uniqueCode(), name, permissionsJson(permissions))).andExpect(status().isCreated()), "$.response.id");
    }
}
