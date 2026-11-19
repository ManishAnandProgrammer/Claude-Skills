package com.example.app.user;

import com.example.app.common.security.Permission;
import com.example.app.support.RolesFixtures;
import com.example.app.support.StatementCounter;
import com.example.app.user.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-request read (the roles piece, house-review AUTHZ-11): a token carries the user's id alone, and every
 * authenticated request reads what they hold now from the tables, in one indexed statement, through
 * {@code TokenPermissions}. {@code /users/me/access} and every check read the same set.
 */
class RolePermissionsIntegrationTest extends RolesFixtures {

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName("A built-in role gives exactly its Role constant's permissions, read from its copy in the table")
    void aBuiltInRoleGivesExactlyItsPermissions(Role role) throws Exception {
        TestUser user = newUserWithRole(role.name());

        assertThat(myPermissions(user.accessToken()))
                .containsExactlyInAnyOrderElementsOf(role.permissions().stream().map(Permission::name).toList());
    }

    @Test
    @DisplayName("Several roles add up, a personal ALLOW adds, a DENY always wins, and each change reaches the next request")
    void rolesAndGrantsAddUpAndADenyWins() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser user = newUserWithRole("USER");
        String neededRole = createRole(admin, NEEDED);

        assign(admin, user.id(), neededRole, null).andExpect(status().isOk());
        assertThat(myPermissions(user.accessToken())).contains(NEEDED.name()).doesNotContain(NEEDING.name());

        putAs(admin, userPermission(user.id(), NEEDING), """
                {"effect": "ALLOW"}""").andExpect(status().isOk());
        assertThat(myPermissions(user.accessToken())).contains(NEEDED.name(), NEEDING.name());

        putAs(admin, userPermission(user.id(), NEEDED), """
                {"effect": "DENY"}""").andExpect(status().isOk());
        // The DENY takes NEEDED, and NEEDING with it: its prerequisite is gone.
        assertThat(myPermissions(user.accessToken())).doesNotContain(NEEDED.name(), NEEDING.name());
    }

    @Test
    @DisplayName("An assignment or a grant past its valid_until gives nothing; before it, it gives")
    void anEndedAssignmentOrGrantGivesNothing() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser user = newUserWithRole("USER");
        String neededRole = createRole(admin, NEEDED);
        assign(admin, user.id(), neededRole, LATER).andExpect(status().isOk());
        assertThat(myPermissions(user.accessToken())).contains(NEEDED.name());

        sql("update user_roles set valid_until = now() - interval '1 second' where user_id = ? and role_id = ?::uuid", user.id(), neededRole);
        assertThat(myPermissions(user.accessToken())).doesNotContain(NEEDED.name());

        putAs(admin, userPermission(user.id(), NEEDED), """
                {"effect": "ALLOW", "validUntil": "%s"}""".formatted(LATER)).andExpect(status().isOk());
        assertThat(myPermissions(user.accessToken())).contains(NEEDED.name());
        sql("update user_permissions set valid_until = now() - interval '1 second' where user_id = ?", user.id());
        assertThat(myPermissions(user.accessToken())).doesNotContain(NEEDED.name());
    }

    @Test
    @DisplayName("A suspended account holds nothing from its next request on, with the token it already has")
    void aSuspendedAccountHoldsNothing() throws Exception {
        TestUser admin = newAdmin();
        assertThat(myPermissions(admin.accessToken())).isNotEmpty();

        sql("update users set status = 'SUSPENDED' where id = ?", admin.id());

        assertThat(myPermissions(admin.accessToken())).isEmpty();
        getAs(admin.accessToken(), ROLES).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("A code in a row that names no permission grants nothing, and the rest of the role still does")
    void anUnknownCodeGrantsNothing() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser user = newUserWithRole("USER");
        String role = createRole(admin, NEEDED);
        sql("""
                insert into role_permissions (id, role_id, permission, created_at, updated_at, version)
                values (uuidv7(), ?::uuid, 'A_REMOVED_PERMISSION', now(), now(), 0)""", role);

        assign(admin, user.id(), role, null).andExpect(status().isOk());

        assertThat(myPermissions(user.accessToken())).contains(NEEDED.name()).doesNotContain("A_REMOVED_PERMISSION");
    }

    @Test
    @DisplayName("Reading what the caller holds is one statement per request, besides the handler's own (none here)")
    void theReadIsOneStatement() throws Exception {
        TestUser user = newUserWithRole("ADMIN");
        getAs(user.accessToken(), MY_ACCESS).andExpect(status().isOk());

        statistics().clear();
        int queries = StatementCounter.record(() -> getAs(user.accessToken(), MY_ACCESS).andExpect(status().isOk()));

        assertThat(statistics().getPrepareStatementCount()).as("Hibernate's statements").isZero();
        assertThat(queries).as("the per-request read's statements").isEqualTo(1);
    }

    @Test
    @DisplayName("GET /users/me/access lists what every check reads: the same set the handlers enforce")
    void accessShowsWhatTheChecksRead() throws Exception {
        TestUser user = newUserWithRole("USER");
        List<String> shown = myPermissions(user.accessToken());

        assertThat(shown).doesNotContain(Permission.ROLES_MANAGE.name());
        getAs(user.accessToken(), ROLES).andExpect(status().isForbidden());
    }
}
