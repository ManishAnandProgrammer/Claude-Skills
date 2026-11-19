package com.example.app.user;

import com.example.app.common.security.Permission;
import com.example.app.common.tenancy.TenantScope;
import com.example.app.support.RolesFixtures;
import com.example.app.support.WithoutTestTenant;
import com.example.app.user.domain.Role;
import com.example.app.user.service.PersonAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The roles piece with tenancy (house-review AUTHZ-11, TEN-08): a tenant's roles are its own, a person's access is on
 * their member row, and adding or removing a member is a change of their access, under the rules every such change
 * keeps (AUTHZ-12), counted in the caller's tenant. Every test makes its own tenants: the safety rules count a
 * tenant's whole team.
 */
@WithoutTestTenant
class TenantAccessIntegrationTest extends RolesFixtures {

    private static final String MEMBERS = "/api/v1/tenant-members";
    // tenancy-row:begin
    /** A fixture's insert names its row's tenant: row mode's tenant tables have no default (TEN-30). */
    private static final String TENANT_COLUMN = "tenant_id,";
    // tenancy-row:end
    // tenancy-schema:begin
    private static final String TENANT_COLUMN = "";
    // tenancy-schema:end
    // session-version:begin
    // A removed manager's older token: refused by the session version before the write reads the caller's access.
    private static final int OLDER_TOKEN_STATUS = 401;
    private static final String OLDER_TOKEN_REFUSAL = "SESSION_VERSION_STALE";
    // session-version:else
    // private static final int OLDER_TOKEN_STATUS = 403;
    // private static final String OLDER_TOKEN_REFUSAL = "ACCESS_DENIED";
    // session-version:end

    @Value("${app.rate-limit.members.add-per-account.requests}")
    private int addsPerAccount;

    @Autowired
    private PersonAccessService access;

    private static String addPayload(String email) {
        return """
                {"email": "%s"}
                """.formatted(email);
    }

    /** The id of the tenant's copy of a built-in role. */
    private String builtInRoleOf(TestTenant tenant, String role) {
        return queryIn(tenant, "select id::text as id from roles where built_in = ?", role).getFirst().get("id").toString();
    }

    /** The codes of the roles the person holds in the tenant. */
    private List<String> rolesOf(TestTenant tenant, UUID userId) {
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> row : queryIn(tenant, """
                select r.code from user_roles a join roles r on r.id = a.role_id where a.user_id = ? order by r.code""", userId)) {
            codes.add(row.get("code").toString());
        }
        return codes;
    }

    private int grantsOf(TestTenant tenant, UUID userId) {
        return queryIn(tenant, "select id from user_permissions where user_id = ?", userId).size();
    }

    private int membershipsOf(TestTenant tenant, UUID userId) {
        return jdbc.queryForObject("select count(*) from tenant_memberships where tenant_id = ? and user_id = ?",
                Integer.class, tenant.id(), userId);
    }

    /** The person's one row in the tenant's own members (TEN-17), read in that tenant. */
    private Map<String, Object> memberRowOf(TestTenant tenant, UUID userId) {
        List<Map<String, Object>> rows = queryIn(tenant, "select * from members where user_id = ?", userId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    /** The tenant's owner: its oldest membership. */
    private UUID ownerOf(TestTenant tenant) {
        return jdbc.queryForObject("select user_id from tenant_memberships where tenant_id = ? order by id limit 1",
                UUID.class, tenant.id());
    }

    /** A new member of the tenant holding a new role of the tenant of exactly these permissions, until LATER. */
    private TestUser holderIn(TestTenant tenant, TestUser admin, Permission... permissions) throws Exception {
        TestUser user = newUserIn(tenant, "USER");
        assign(admin.accessToken(), user.id(), createRole(admin.accessToken(), permissions), LATER).andExpect(status().isOk());
        return user;
    }

    @Test
    @DisplayName("Adding a member gives them the tenant's built-in USER role (201, no role field); the add's errors are 404 and 409")
    void anAddedMemberHoldsTheUserRole() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser existing = newUserIn(tenant, "USER");
        String email = uniqueEmail("colleague");
        UUID colleague = UUID.fromString(read(rawSignup(email, PASSWORD).andExpect(status().isCreated()), "$.response.id"));

        postAs(admin.accessToken(), MEMBERS, addPayload(email.toUpperCase()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.email").value(email))
                .andExpect(jsonPath("$.response.role").doesNotExist())
                .andExpect(jsonPath("$.response.createdBy").value(admin.id().toString()));

        assertThat(rolesOf(tenant, colleague)).containsExactly("USER");
        getAs(admin.accessToken(), userAccess(colleague))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.roles[*].code", hasItem("USER")));
        postAs(admin.accessToken(), MEMBERS, addPayload(uniqueEmail("nobody")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_ACCOUNT_NOT_FOUND"));
        postAs(admin.accessToken(), MEMBERS, addPayload(existing.email()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_EXISTS"));
        assertThat(rolesOf(tenant, existing.id())).containsExactly("USER");
    }

    @Test
    @DisplayName("""
            Safety rule 1 on an add: a caller who manages the members but lacks a permission of the tenant's USER role \
            adds nobody (403 ACCESS_NOT_HELD)""")
    void anAddGivesOnlyWhatTheCallerHolds() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser manager = holderIn(tenant, admin, Permission.TENANT_MEMBERS_MANAGE);
        sqlIn(tenant, "delete from user_roles where user_id = ? and role_id = (select id from roles where built_in = 'USER')",
                manager.id());
        // The tenant's copy of USER, edited to hold what the manager doesn't (a copy: the enum is untouched).
        sqlIn(tenant, """
                insert into role_permissions (id, %s role_id, permission, created_at, updated_at, version)
                select uuidv7(), %s id, 'ROLES_MANAGE', now(), now(), 0 from roles where built_in = 'USER'"""
                .formatted(TENANT_COLUMN, TENANT_COLUMN));
        String email = uniqueEmail("colleague");
        rawSignup(email, PASSWORD).andExpect(status().isCreated());

        try {
            postAs(manager.accessToken(), MEMBERS, addPayload(email))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("ACCESS_NOT_HELD"));
            Integer added = jdbc.queryForObject("""
                    select count(*) from tenant_memberships m join users u on u.id = m.user_id where u.email = ?""", Integer.class, email);
            assertThat(added).isZero();
        } finally {
            // Built-in copies are compared with the enum in every tenant (BuiltInRolesIntegrationTest).
            sqlIn(tenant, """
                    delete from role_permissions
                     where permission = 'ROLES_MANAGE' and role_id = (select id from roles where built_in = 'USER')""");
        }
    }

    @Test
    @DisplayName("""
            A caller's adds are limited per account: past the limit 429 RATE_LIMITED with Retry-After, even for an \
            existing account; another manager still adds""")
    void addsAreLimitedPerCaller() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser otherAdmin = newUserIn(tenant, "ADMIN");
        String email = uniqueEmail("colleague");
        rawSignup(email, PASSWORD).andExpect(status().isCreated());

        for (int lookup = 0; lookup < addsPerAccount; lookup++) {
            postAs(admin.accessToken(), MEMBERS, addPayload(uniqueEmail("nobody"))).andExpect(status().isNotFound());
        }
        postAs(admin.accessToken(), MEMBERS, addPayload(email))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        postAs(otherAdmin.accessToken(), MEMBERS, addPayload(email)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("""
            A removal takes the member's roles and grants in the tenant with the membership; added again they start \
            with USER alone, on the same member row, marked left in between""")
    void aRemovalTakesTheAccessAway() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser member = holderIn(tenant, admin, Permission.TENANT_MEMBERS_MANAGE);
        putAs(admin.accessToken(), userPermission(member.id(), Permission.ROLES_MANAGE), """
                {"effect": "ALLOW", "validUntil": "%s"}""".formatted(LATER)).andExpect(status().isOk());
        Map<String, Object> added = memberRowOf(tenant, member.id());

        deleteAs(admin.accessToken(), MEMBERS + "/" + member.id()).andExpect(status().isNoContent());

        assertThat(membershipsOf(tenant, member.id())).isZero();
        assertThat(rolesOf(tenant, member.id())).isEmpty();
        assertThat(grantsOf(tenant, member.id())).isZero();
        assertThat(memberRowOf(tenant, member.id()).get("left_at")).isNotNull();
        postAs(admin.accessToken(), MEMBERS, addPayload(member.email())).andExpect(status().isCreated());
        assertThat(rolesOf(tenant, member.id())).containsExactly("USER");
        assertThat(grantsOf(tenant, member.id())).isZero();
        Map<String, Object> back = memberRowOf(tenant, member.id());
        assertThat(back).doesNotContainKeys("email", "full_name", "role").containsEntry("left_at", null);
        assertThat(back.get("id")).isEqualTo(added.get("id"));
    }

    @Test
    @DisplayName("Provisioning gives a new tenant's owner their member row and the tenant's ADMIN role, for good")
    void provisioningGivesTheOwnerTheAdminRole() throws Exception {
        TestTenant tenant = newTenant();
        UUID owner = ownerOf(tenant);

        assertThat(memberRowOf(tenant, owner).get("left_at")).isNull();
        assertThat(rolesOf(tenant, owner)).containsExactly("ADMIN");
        assertThat(queryIn(tenant, "select valid_until from user_roles where user_id = ?", owner).getFirst().get("valid_until")).isNull();
    }

    @Test
    @DisplayName("Safety rule 3 on a removal: a manager never removes a member who holds a permission they don't (403 ACCESS_TARGET_HOLDS_MORE)")
    void nobodyRemovesSomeoneAboveThem() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser manager = holderIn(tenant, admin, Permission.TENANT_MEMBERS_MANAGE);

        deleteAs(manager.accessToken(), MEMBERS + "/" + admin.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_TARGET_HOLDS_MORE"));
        assertThat(membershipsOf(tenant, admin.id())).isOne();
        deleteAs(manager.accessToken(), MEMBERS + "/" + newUserIn(tenant, "USER").id()).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("""
            Safety rule 6 per tenant: removing the last active member who holds, for good, what manages the tenant is \
            409 ACCESS_LAST_MANAGER, even by a caller holding all of it until a date""")
    void theLastManagerOfATenantIsKept() throws Exception {
        TestTenant tenant = newTenant();
        UUID owner = ownerOf(tenant);
        TestUser expiring = newUserIn(tenant, "ADMIN");
        sqlIn(tenant, "update user_roles set valid_until = ? where user_id = ?", Timestamp.from(LATER), expiring.id());

        deleteAs(expiring.accessToken(), MEMBERS + "/" + owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_LAST_MANAGER"));
        assertThat(membershipsOf(tenant, owner)).isOne();
        assertThat(rolesOf(tenant, owner)).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("""
            Safety rule 6 per tenant on a role's edit: taking ROLES_MANAGE or ACCESS_GRANT from the tenant's ADMIN \
            role, the only one giving them for good, is 409 ACCESS_LAST_MANAGER, a holder until a date notwithstanding""")
    void aRoleEditKeepsTheTenantsManagers() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        holderIn(tenant, admin, Permission.ROLES_MANAGE, Permission.ACCESS_GRANT, Permission.TENANT_MEMBERS_MANAGE);
        String adminRole = builtInRoleOf(tenant, "ADMIN");
        Object version = queryIn(tenant, "select version from roles where built_in = 'ADMIN'").getFirst().get("version");

        // TENANT_MEMBERS_MANAGE is ACCESS_GRANT's prerequisite: an edit without it alone is refused by rule 4 first.
        for (Permission kept : List.of(Permission.ROLES_MANAGE, Permission.ACCESS_GRANT)) {
            List<String> without = Role.ADMIN.permissions().stream().filter(permission -> permission != kept)
                    .map(permission -> "\"" + permission + "\"").toList();
            putAs(admin.accessToken(), ROLES + "/" + adminRole, """
                    {"name": "Admin", "permissions": %s, "version": %s}""".formatted(without, version))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("ACCESS_LAST_MANAGER"));
        }
        assertThat(queryIn(tenant, "select version from roles where built_in = 'ADMIN'").getFirst().get("version")).isEqualTo(version);
    }

    @Test
    @DisplayName("Two managers removing each other at once leave one: one 204, the other refused, one membership gone")
    void concurrentRemovalsKeepAManager() throws Exception {
        TestTenant tenant = newTenant();
        TestUser first = newUserIn(tenant, "ADMIN");
        TestUser second = newUserIn(tenant, "ADMIN");
        List<Callable<ResultActions>> removals = List.of(
                () -> deleteAs(first.accessToken(), MEMBERS + "/" + second.id()),
                () -> deleteAs(second.accessToken(), MEMBERS + "/" + first.id()));

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Answer>> pending = new ArrayList<>();
        // Plain threads: each request takes its tenant from its own token.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<ResultActions> removal : removals) {
                Callable<Answer> once = () -> {
                    awaitLatch(start);
                    return answerOf(removal.call());
                };
                pending.add(executor.submit(once));
            }
            start.countDown();
            List<Answer> answers = new ArrayList<>();
            for (Future<Answer> answer : pending) {
                answers.add(within(answer));
            }
            // The second to take the tenant's lock is no member any more; or its token is already stale.
            assertThat(answers).extracting(Answer::status).containsOnlyOnce(204).hasSize(2);
            assertThat(answers).filteredOn(answer -> answer.status() != 204).extracting(Answer::errorCode)
                    .first().isIn("ACCESS_DENIED", "SESSION_VERSION_STALE");
        }
        assertThat(membershipsOf(tenant, first.id()) + membershipsOf(tenant, second.id())).isOne();
    }

    @Test
    @DisplayName("A removed manager's older token adds nobody and can't come back: still no membership")
    void aRemovedManagerManagesNoMore() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser second = newUserIn(tenant, "ADMIN");
        String email = uniqueEmail("sock-puppet");
        rawSignup(email, PASSWORD).andExpect(status().isCreated());

        deleteAs(admin.accessToken(), MEMBERS + "/" + second.id()).andExpect(status().isNoContent());

        postAs(second.accessToken(), MEMBERS, addPayload(second.email()))
                .andExpect(status().is(OLDER_TOKEN_STATUS))
                .andExpect(jsonPath("$.errorCode").value(OLDER_TOKEN_REFUSAL));
        postAs(second.accessToken(), MEMBERS, addPayload(email))
                .andExpect(status().is(OLDER_TOKEN_STATUS))
                .andExpect(jsonPath("$.errorCode").value(OLDER_TOKEN_REFUSAL));
        assertThat(membershipsOf(tenant, second.id())).isZero();
    }

    @Test
    @DisplayName("Removing one's own membership is 403 TENANT_MEMBER_OWN_CHANGE; a USER member manages nobody (403); no token 401")
    void onlyManagersRemoveOthers() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "USER");

        deleteAs(admin.accessToken(), MEMBERS + "/" + admin.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_OWN_CHANGE"));
        deleteAs(member.accessToken(), MEMBERS + "/" + admin.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        postAs(member.accessToken(), MEMBERS, addPayload(uniqueEmail("candidate")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        deleteAs(null, MEMBERS + "/" + admin.id()).andExpect(status().isUnauthorized());
        assertThat(membershipsOf(tenant, admin.id())).isOne();
    }

    @Test
    @DisplayName("""
            Another tenant's member, or an unknown user, is 404 TENANT_MEMBER_NOT_FOUND for a removal, their access and \
            its changes, and stays as they were""")
    void anotherTenantsMemberIs404() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser admin = newUserIn(mine, "ADMIN");
        TestUser stranger = newUserIn(theirs, "USER");

        deleteAs(admin.accessToken(), MEMBERS + "/" + stranger.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
        getAs(admin.accessToken(), userAccess(stranger.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
        assign(admin.accessToken(), stranger.id(), builtInRoleOf(mine, "ADMIN"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
        getAs(admin.accessToken(), userAccess(UUID.randomUUID())).andExpect(status().isNotFound());
        assertThat(rolesOf(theirs, stranger.id())).containsExactly("USER");
        assertThat(membershipsOf(theirs, stranger.id())).isOne();
    }

    @Test
    @DisplayName("""
            A tenant's roles are its own: another tenant's role is 404 ROLE_NOT_FOUND, its list never shows it, and a \
            person of both holds in each tenant only what that tenant gives them""")
    void rolesStayInTheirTenant() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser admin = newUserIn(mine, "ADMIN");
        TestUser theirAdmin = newUserIn(theirs, "ADMIN");
        TestUser both = newUserIn(theirs, "USER");
        addMember(mine, both.id(), "USER");
        String theirRole = createRole(theirAdmin.accessToken(), Permission.TENANT_MEMBERS_MANAGE);

        getAs(admin.accessToken(), ROLES + "/" + theirRole)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ROLE_NOT_FOUND"));
        assign(admin.accessToken(), both.id(), theirRole, LATER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ROLE_NOT_FOUND"));
        List<String> listed = read(getAs(admin.accessToken(), ROLES + "?size=100").andExpect(status().isOk()), "$.response[*].id");
        assertThat(listed).doesNotContain(theirRole);
        assign(admin.accessToken(), both.id(), builtInRoleOf(mine, "ADMIN"), null).andExpect(status().isOk());

        assertThat(rolesOf(mine, both.id())).containsExactly("ADMIN", "USER");
        assertThat(rolesOf(theirs, both.id())).containsExactly("USER");
        // Signed in to their tenant, the person holds its USER role alone, whatever they hold in mine.
        assertThat(myPermissions(both.accessToken())).doesNotContain(Permission.TENANT_MEMBERS_MANAGE.name());
    }

    @Test
    @DisplayName("A member of two tenants is removed in the caller's tenant only: the other membership and its roles stay")
    void aRemovalStaysInTheCallersTenant() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser admin = newUserIn(mine, "ADMIN");
        TestUser both = newUserIn(theirs, "ADMIN");
        addMember(mine, both.id(), "USER");

        deleteAs(admin.accessToken(), MEMBERS + "/" + both.id()).andExpect(status().isNoContent());

        assertThat(membershipsOf(mine, both.id())).isZero();
        assertThat(membershipsOf(theirs, both.id())).isOne();
        assertThat(rolesOf(theirs, both.id())).containsExactly("ADMIN");
        getAs(both.accessToken(), MEMBERS).andExpect(status().isOk());
    }

    @Test
    @DisplayName("""
            The per-request read joins the account, the membership and the tenant: a suspended tenant, a membership \
            gone or a suspended account hold nothing in the tenant, whatever its tables still give""")
    void theReadJoinsTheAccountTheMembershipAndTheTenant() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        assertThat(heldIn(tenant, admin.id())).contains(Permission.TENANT_MEMBERS_MANAGE);

        sql("update tenants set status = 'SUSPENDED' where id = ?", tenant.id());
        assertThat(heldIn(tenant, admin.id())).isEmpty();
        sql("update tenants set status = 'ACTIVE' where id = ?", tenant.id());
        sql("update users set status = 'SUSPENDED' where id = ?", admin.id());
        assertThat(heldIn(tenant, admin.id())).isEmpty();
        sql("update users set status = 'ACTIVE' where id = ?", admin.id());
        assertThat(heldIn(tenant, admin.id())).contains(Permission.TENANT_MEMBERS_MANAGE);
        sql("delete from tenant_memberships where tenant_id = ? and user_id = ?", tenant.id(), admin.id());
        assertThat(heldIn(tenant, admin.id())).isEmpty();
        assertThat(rolesOf(tenant, admin.id())).containsExactly("ADMIN");
    }

    /** What the per-request read gives the person in the tenant, as the security chain reads it. */
    private Set<Permission> heldIn(TestTenant tenant, UUID userId) {
        return TenantScope.callAs(tenant.id(), () -> access.effectivePermissions(userId));
    }

    @Test
    @DisplayName("A removed member added again starts afresh: the refresh token from before the removal is 401 AUTH_INVALID_REFRESH_TOKEN")
    void aReAddedMembersOldSessionStaysEnded() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "USER");

        deleteAs(admin.accessToken(), MEMBERS + "/" + member.id()).andExpect(status().isNoContent());
        postAs(admin.accessToken(), MEMBERS, addPayload(member.email())).andExpect(status().isCreated());

        postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}
                """.formatted(member.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        login(member.email(), PASSWORD).andExpect(status().isOk());
    }
}
