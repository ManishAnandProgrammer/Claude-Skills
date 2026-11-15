package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A tenant ADMIN manages who belongs to their tenant; every tenant keeps an ADMIN. */
@WithoutTestTenant
class TenantMemberIntegrationTest extends IntegrationTest {

    private static final String MEMBERS = "/api/v1/tenant-members";
    // session-version:begin
    // An older token of a demoted or removed ADMIN: refused by the session version before the write's own
    // check of the caller's current role (which stays, and is what refuses it without the session version).
    private static final int OLDER_TOKEN_STATUS = 401;
    private static final String OLDER_TOKEN_REFUSAL = "SESSION_VERSION_STALE";
    // session-version:else
    // private static final int OLDER_TOKEN_STATUS = 403;
    // private static final String OLDER_TOKEN_REFUSAL = "ACCESS_DENIED";
    // session-version:end

    private static String addPayload(String email, String role) {
        return """
                {"email": "%s", "role": "%s"}
                """.formatted(email, role);
    }

    private static String rolePayload(String role, long version) {
        return """
                {"role": "%s", "version": %d}
                """.formatted(role, version);
    }

    /** A new account that is the tenant's only ADMIN (its owner is made a USER). */
    private TestUser soleAdminOf(TestTenant tenant) throws Exception {
        TestUser admin = newUserIn(tenant, "ADMIN");
        sql("update tenant_memberships set role = 'USER' where tenant_id = ? and user_id <> ?", tenant.id(), admin.id());
        return admin;
    }

    private String roleOf(TestTenant tenant, UUID userId) {
        return jdbc.queryForObject("select role from tenant_memberships where tenant_id = ? and user_id = ?",
                String.class, tenant.id(), userId);
    }

    private long versionOf(TestTenant tenant, UUID userId) {
        return jdbc.queryForObject("select version from tenant_memberships where tenant_id = ? and user_id = ?",
                Long.class, tenant.id(), userId);
    }

    /** The person's one row in the tenant's own members (TEN-17), read in that tenant. */
    private Map<String, Object> memberRowOf(TestTenant tenant, UUID userId) {
        List<Map<String, Object>> rows = queryIn(tenant, "select * from members where user_id = ?", userId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private int adminsOf(TestTenant tenant) {
        return jdbc.queryForObject("select count(*) from tenant_memberships where tenant_id = ? and role = 'ADMIN'",
                Integer.class, tenant.id());
    }

    @Test
    @DisplayName("An ADMIN adds an existing account with a role: 201 with the member, who can then sign in to the tenant")
    void adminAddsAnExistingAccount() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        String email = uniqueEmail("colleague");
        rawSignup(email, PASSWORD).andExpect(status().isCreated());

        postAs(owner.accessToken(), MEMBERS, addPayload(email.toUpperCase(), "USER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.email").value(email))
                .andExpect(jsonPath("$.response.role").value("USER"))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdBy").value(owner.id().toString()));

        String colleagueToken = read(login(email, PASSWORD).andExpect(status().isOk()), "$.response.accessToken");
        getAs(colleagueToken, "/api/v1/users/me/tenants")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].tenantId", hasItem(tenant.id().toString())));
    }

    @Test
    @DisplayName("Adding an email without an account is 404 TENANT_MEMBER_ACCOUNT_NOT_FOUND; adding a member again 409 TENANT_MEMBER_EXISTS")
    void addRefusesUnknownAccountsAndMembers() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser member = newUserIn(tenant, "USER");

        postAs(owner.accessToken(), MEMBERS, addPayload(uniqueEmail("nobody"), "USER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_ACCOUNT_NOT_FOUND"));
        String body = postAs(owner.accessToken(), MEMBERS, addPayload(member.email(), "ADMIN"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_EXISTS"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        assertThat(roleOf(tenant, member.id())).isEqualTo("USER");
    }

    @Test
    @DisplayName("An ADMIN changes a member's role: 200, the version moves; a stale version is 409 and changes nothing")
    void adminChangesARole() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser member = newUserIn(tenant, "USER");

        long readVersion = versionOf(tenant, member.id());

        putAs(owner.accessToken(), MEMBERS + "/" + member.id(), rolePayload("ADMIN", readVersion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.role").value("ADMIN"))
                .andExpect(jsonPath("$.response.version").value(readVersion + 1));
        putAs(owner.accessToken(), MEMBERS + "/" + member.id(), rolePayload("USER", readVersion))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
        assertThat(roleOf(tenant, member.id())).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("An ADMIN removes a member: 204, the membership is gone and its refresh token no longer works there")
    void adminRemovesAMember() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser member = newUserIn(tenant, "USER");

        deleteAs(owner.accessToken(), MEMBERS + "/" + member.id()).andExpect(status().isNoContent());

        Integer memberships = jdbc.queryForObject("select count(*) from tenant_memberships where tenant_id = ? and user_id = ?",
                Integer.class, tenant.id(), member.id());
        assertThat(memberships).isZero();
        postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}
                """.formatted(member.refreshToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MEMBERSHIP_NOT_FOUND"));
    }

    @Test
    @DisplayName("""
            A member's row in the tenant's own data comes with the membership, without personal data; a removed \
            member's row stays, marked left, and adding them again clears the mark on the same row""")
    void theTenantsMembersFollowTheMemberships() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        String email = uniqueEmail("colleague");
        UUID colleague = UUID.fromString(read(rawSignup(email, PASSWORD).andExpect(status().isCreated()), "$.response.id"));

        postAs(owner.accessToken(), MEMBERS, addPayload(email, "USER")).andExpect(status().isCreated());
        Map<String, Object> added = memberRowOf(tenant, colleague);
        deleteAs(owner.accessToken(), MEMBERS + "/" + colleague).andExpect(status().isNoContent());
        Map<String, Object> removed = memberRowOf(tenant, colleague);
        postAs(owner.accessToken(), MEMBERS, addPayload(email, "USER")).andExpect(status().isCreated());
        Map<String, Object> back = memberRowOf(tenant, colleague);

        assertThat(added).doesNotContainKeys("email", "full_name").containsEntry("left_at", null);
        assertThat(removed.get("id")).isEqualTo(added.get("id"));
        assertThat(removed.get("left_at")).isNotNull();
        assertThat(back.get("id")).isEqualTo(added.get("id"));
        assertThat(back.get("left_at")).isNull();
    }

    @Test
    @DisplayName("Provisioning gives a new tenant's owner their row in the tenant's own members")
    void provisioningAddsTheOwnersRow() throws Exception {
        TestTenant tenant = newTenant();
        UUID ownerId = jdbc.queryForObject("select user_id from tenant_memberships where tenant_id = ? and role = 'ADMIN'",
                UUID.class, tenant.id());

        assertThat(memberRowOf(tenant, ownerId).get("left_at")).isNull();
    }

    @Test
    @DisplayName("A removed member added again starts afresh: the refresh token from before the removal is 401 AUTH_INVALID_REFRESH_TOKEN")
    void aReAddedMembersOldSessionStaysEnded() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser member = newUserIn(tenant, "USER");

        deleteAs(owner.accessToken(), MEMBERS + "/" + member.id()).andExpect(status().isNoContent());
        postAs(owner.accessToken(), MEMBERS, addPayload(member.email(), "USER")).andExpect(status().isCreated());

        postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}
                """.formatted(member.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        login(member.email(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A removed member's old refresh token switches into no tenant: to another, or back to the re-added one, 401")
    void aRemovedMembersOldSessionDoesNotSwitch() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser member = newUserIn(tenant, "USER");
        TestTenant other = newTenant();
        addMember(other, member.id(), "USER");

        deleteAs(owner.accessToken(), MEMBERS + "/" + member.id()).andExpect(status().isNoContent());
        switchTenant(member.refreshToken(), other)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        postAs(owner.accessToken(), MEMBERS, addPayload(member.email(), "USER")).andExpect(status().isCreated());
        switchTenant(member.refreshToken(), tenant)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        switchTenant(member.refreshToken(), other)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        login(member.email(), PASSWORD).andExpect(status().isOk());
    }

    private ResultActions switchTenant(String refreshToken, TestTenant tenant) throws Exception {
        return postAs(null, "/api/v1/auth/switch-tenant", """
                {"refreshToken": "%s", "tenantId": "%s"}
                """.formatted(refreshToken, tenant.id()));
    }

    @Test
    // session-version:begin
    @DisplayName("A demoted ADMIN's older token is 401 SESSION_VERSION_STALE: it can't demote, remove, promote or add, so the last ADMIN stays")
    // session-version:else
    // @DisplayName("A demoted ADMIN's older token is 403 ACCESS_DENIED: it can't demote, remove, promote or add, so the last ADMIN stays")
    // session-version:end
    void aDemotedAdminsTokenManagesNoMore() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser second = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "USER");
        String email = uniqueEmail("sock-puppet");
        rawSignup(email, PASSWORD).andExpect(status().isCreated());
        putAs(second.accessToken(), MEMBERS + "/" + owner.id(), rolePayload("USER", versionOf(tenant, owner.id()))).andExpect(status().isOk());

        // The owner's token still says ADMIN until it expires; second is now the only ADMIN.
        putAs(owner.accessToken(), MEMBERS + "/" + second.id(), rolePayload("USER", versionOf(tenant, second.id())))
                .andExpect(status().is(OLDER_TOKEN_STATUS))
                .andExpect(jsonPath("$.errorCode").value(OLDER_TOKEN_REFUSAL));
        deleteAs(owner.accessToken(), MEMBERS + "/" + second.id())
                .andExpect(status().is(OLDER_TOKEN_STATUS))
                .andExpect(jsonPath("$.errorCode").value(OLDER_TOKEN_REFUSAL));
        putAs(owner.accessToken(), MEMBERS + "/" + member.id(), rolePayload("ADMIN", versionOf(tenant, member.id())))
                .andExpect(status().is(OLDER_TOKEN_STATUS))
                .andExpect(jsonPath("$.errorCode").value(OLDER_TOKEN_REFUSAL));
        postAs(owner.accessToken(), MEMBERS, addPayload(email, "ADMIN"))
                .andExpect(status().is(OLDER_TOKEN_STATUS))
                .andExpect(jsonPath("$.errorCode").value(OLDER_TOKEN_REFUSAL));
        assertThat(roleOf(tenant, second.id())).isEqualTo("ADMIN");
        assertThat(roleOf(tenant, member.id())).isEqualTo("USER");
        assertThat(adminsOf(tenant)).isEqualTo(1);
        Integer added = jdbc.queryForObject("""
                select count(*) from tenant_memberships m join users u on u.id = m.user_id where u.email = ?
                """, Integer.class, email);
        assertThat(added).isZero();
    }

    @Test
    // session-version:begin
    @DisplayName("A removed ADMIN's older token can't add them back: 401 SESSION_VERSION_STALE, still no membership")
    // session-version:else
    // @DisplayName("A removed ADMIN's older token can't add them back: 403 ACCESS_DENIED, still no membership")
    // session-version:end
    void aRemovedAdminCantComeBack() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser second = newUserIn(tenant, "ADMIN");

        deleteAs(owner.accessToken(), MEMBERS + "/" + second.id()).andExpect(status().isNoContent());

        postAs(second.accessToken(), MEMBERS, addPayload(second.email(), "ADMIN"))
                .andExpect(status().is(OLDER_TOKEN_STATUS))
                .andExpect(jsonPath("$.errorCode").value(OLDER_TOKEN_REFUSAL));
        Integer memberships = jdbc.queryForObject("select count(*) from tenant_memberships where tenant_id = ? and user_id = ?",
                Integer.class, tenant.id(), second.id());
        assertThat(memberships).isZero();
    }

    @Test
    @DisplayName("A member's sign-ins and refreshes leave the membership's version alone: the version the add answered still changes the role")
    void tokenIssuesKeepTheVersion() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        String email = uniqueEmail("colleague");
        rawSignup(email, PASSWORD).andExpect(status().isCreated());
        ResultActions added = postAs(owner.accessToken(), MEMBERS, addPayload(email, "USER")).andExpect(status().isCreated());
        UUID userId = UUID.fromString(read(added, "$.response.userId"));
        Integer addedVersion = read(added, "$.response.version");

        String refreshToken = read(login(email, PASSWORD).andExpect(status().isOk()), "$.response.refreshToken");
        postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken)).andExpect(status().isOk());
        login(email, PASSWORD).andExpect(status().isOk());

        assertThat(versionOf(tenant, userId)).isEqualTo(addedVersion.longValue());
        Integer stamped = jdbc.queryForObject("""
                select count(*) from tenant_memberships where tenant_id = ? and user_id = ? and last_used_at is not null
                """, Integer.class, tenant.id(), userId);
        assertThat(stamped).isOne();
        putAs(owner.accessToken(), MEMBERS + "/" + userId, rolePayload("ADMIN", addedVersion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(addedVersion + 1));
    }

    @Test
    @DisplayName("Two ADMINs demoting each other at once leave exactly one ADMIN: one 200, the other 403 ACCESS_DENIED")
    void concurrentDemotionsKeepAnAdmin() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser second = newUserIn(tenant, "ADMIN");
        String demoteSecond = rolePayload("USER", versionOf(tenant, second.id()));
        String demoteOwner = rolePayload("USER", versionOf(tenant, owner.id()));
        List<Callable<ResultActions>> demotions = List.of(
                () -> putAs(owner.accessToken(), MEMBERS + "/" + second.id(), demoteSecond),
                () -> putAs(second.accessToken(), MEMBERS + "/" + owner.id(), demoteOwner));

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Answer>> pending = new ArrayList<>();
        // Plain threads, as race() uses: newExecutor() would run them in the run's test tenant, and each
        // request must take its tenant from its own token.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<ResultActions> demotion : demotions) {
                Callable<Answer> once = () -> {
                    awaitLatch(start);
                    return answerOf(demotion.call());
                };
                pending.add(executor.submit(once));
            }
            start.countDown();
            List<Answer> answers = new ArrayList<>();
            for (Future<Answer> answer : pending) {
                answers.add(within(answer));
            }
            // The second to take the tenant's lock is no ADMIN any more.
            // session-version:begin
            // Or, when its request starts after the first one committed, its token is already stale.
            assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).hasSize(2);
            assertThat(answers).filteredOn(answer -> answer.status() != 200).extracting(Answer::errorCode)
                    .first().isIn("ACCESS_DENIED", "SESSION_VERSION_STALE");
            // session-version:else
            // assertThat(answers).extracting(Answer::status).containsExactlyInAnyOrder(200, 403);
            // assertRefusalsAre(answers, 403, "ACCESS_DENIED");
            // session-version:end
        }
        assertThat(adminsOf(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("Changing or removing one's own membership is 403 TENANT_MEMBER_OWN_CHANGE, whatever the state")
    void nobodyChangesTheirOwnMembership() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        newUserIn(tenant, "ADMIN");

        putAs(owner.accessToken(), MEMBERS + "/" + owner.id(), rolePayload("USER", versionOf(tenant, owner.id())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_OWN_CHANGE"));
        deleteAs(owner.accessToken(), MEMBERS + "/" + owner.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_OWN_CHANGE"));
        assertThat(roleOf(tenant, owner.id())).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Another tenant's member, or an unknown user, is 404 TENANT_MEMBER_NOT_FOUND and stays as it was")
    void anotherTenantsMemberIs404() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser owner = soleAdminOf(mine);
        TestUser stranger = newUserIn(theirs, "USER");

        putAs(owner.accessToken(), MEMBERS + "/" + stranger.id(), rolePayload("ADMIN", versionOf(theirs, stranger.id())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
        deleteAs(owner.accessToken(), MEMBERS + "/" + stranger.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
        deleteAs(owner.accessToken(), MEMBERS + "/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
        assertThat(roleOf(theirs, stranger.id())).isEqualTo("USER");
    }

    @Test
    @DisplayName("A member of two tenants is changed and removed in the caller's tenant only: the other membership stays as it was")
    void changesStayInTheCallersTenant() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser owner = soleAdminOf(mine);
        TestUser both = newUserIn(theirs, "USER");
        addMember(mine, both.id(), "USER");
        long theirVersion = versionOf(theirs, both.id());

        putAs(owner.accessToken(), MEMBERS + "/" + both.id(), rolePayload("ADMIN", versionOf(mine, both.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.role").value("ADMIN"));
        assertThat(roleOf(theirs, both.id())).isEqualTo("USER");
        deleteAs(owner.accessToken(), MEMBERS + "/" + both.id()).andExpect(status().isNoContent());

        Integer leftInMine = jdbc.queryForObject("select count(*) from tenant_memberships where tenant_id = ? and user_id = ?",
                Integer.class, mine.id(), both.id());
        assertThat(leftInMine).isZero();
        assertThat(roleOf(theirs, both.id())).isEqualTo("USER");
        assertThat(versionOf(theirs, both.id())).isEqualTo(theirVersion);
    }

    @Test
    @DisplayName("A USER member is 403 ACCESS_DENIED on every call, with valid bodies; without a token 401")
    void onlyAdminsManageMembers() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser member = newUserIn(tenant, "USER");
        String email = uniqueEmail("candidate");
        rawSignup(email, PASSWORD).andExpect(status().isCreated());

        postAs(member.accessToken(), MEMBERS, addPayload(email, "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        putAs(member.accessToken(), MEMBERS + "/" + owner.id(), rolePayload("USER", versionOf(tenant, owner.id())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        deleteAs(member.accessToken(), MEMBERS + "/" + owner.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        postAs(null, MEMBERS, addPayload(email, "USER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        assertThat(roleOf(tenant, owner.id())).isEqualTo("ADMIN");
        Integer candidates = jdbc.queryForObject("""
                select count(*) from tenant_memberships m join users u on u.id = m.user_id where u.email = ?
                """, Integer.class, email);
        assertThat(candidates).isZero();
    }
}
