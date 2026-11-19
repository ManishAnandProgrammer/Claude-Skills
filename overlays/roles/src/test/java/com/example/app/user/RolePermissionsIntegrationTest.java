package com.example.app.user;

import com.example.app.common.security.Permission;
import com.example.app.support.RolesFixtures;
import com.example.app.support.StatementCounter;
import com.example.app.user.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-request read (the roles piece, house-review AUTHZ-11): a token carries the user's id alone, and every
 * authenticated request reads what they hold now from the tables, in one indexed statement, through
 * {@code TokenPermissions}. {@code /users/me/access} and every check read the same set.
 */
class RolePermissionsIntegrationTest extends RolesFixtures {

    @Autowired
    private JwtAuthenticationConverter converter;

    @Autowired
    private JwtDecoder jwtDecoder;

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName("A built-in role gives exactly its Role constant's permissions, read from its copy in the table")
    void aBuiltInRoleGivesExactlyItsPermissions(Role role) throws Exception {
        TestUser user = newUserWithRole(role.name());

        assertThat(myPermissions(user.accessToken()))
                .containsExactlyInAnyOrderElementsOf(role.permissions().stream().map(Permission::name).toList());
    }

    @Test
    @DisplayName("The converter gives exactly the permissions held, by name, beside Spring's FACTOR_BEARER; an unknown subject nothing")
    void theConverterGivesExactlyWhatIsHeld() throws Exception {
        TestUser user = newUserWithRole("ADMIN");
        Jwt token = jwtDecoder.decode(user.accessToken());

        List<String> authorities = converter.convert(token).getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).containsExactlyInAnyOrderElementsOf(Stream.concat(
                Role.ADMIN.permissions().stream().map(Permission::name), Stream.of(FactorGrantedAuthority.BEARER_AUTHORITY)).toList());
        Jwt stranger = Jwt.withTokenValue("probe").header("alg", "HS256").subject(UUID.randomUUID().toString()).build();
        assertThat(converter.convert(stranger).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(FactorGrantedAuthority.BEARER_AUTHORITY);
    }

    @Test
    @DisplayName("Several roles add up, a personal ALLOW adds, a DENY always wins, and each change reaches the next request")
    void rolesAndGrantsAddUpAndADenyWins() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser user = newUserWithRole("USER");
        String neededRole = createRole(admin, NEEDED);

        assign(admin, user.id(), neededRole, null).andExpect(status().isOk());
        assertThat(myPermissions(user.accessToken())).contains(NEEDED.name()).doesNotContain(NEEDING.name())
                .containsAll(Role.USER.permissions().stream().map(Permission::name).toList());

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
        String admin = newAdmin().accessToken();
        String role = createRole(admin, Permission.ROLES_MANAGE);
        assign(admin, user.id(), role, LATER).andExpect(status().isOk());
        assertThat(myPermissions(user.accessToken())).contains(Permission.ROLES_MANAGE.name());
        getAs(user.accessToken(), ROLES).andExpect(status().isOk());

        deleteAs(admin, userRole(user.id(), role)).andExpect(status().isNoContent());

        assertThat(myPermissions(user.accessToken())).doesNotContain(Permission.ROLES_MANAGE.name());
        getAs(user.accessToken(), ROLES).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }
}
