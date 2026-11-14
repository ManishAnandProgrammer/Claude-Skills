package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
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

    /** A new account with {@code role} in {@code tenant} only, signed in there. */
    private TestUser memberIn(TestTenant tenant, String role) throws Exception {
        TestUser member = newUserIn(tenant);
        assertThat(sql("update tenant_memberships set role = ? where tenant_id = ? and user_id = ?",
                role, tenant.id(), member.id())).isEqualTo(1);
        return signIn(member);
    }

    /** A new account that is the tenant's only ADMIN (its owner is made a USER). */
    private TestUser soleAdminOf(TestTenant tenant) throws Exception {
        TestUser admin = memberIn(tenant, "ADMIN");
        sql("update tenant_memberships set role = 'USER' where tenant_id = ? and user_id <> ?", tenant.id(), admin.id());
        return admin;
    }

    private String roleOf(TestTenant tenant, UUID userId) {
        return jdbc.queryForObject("select role from tenant_memberships where tenant_id = ? and user_id = ?",
                String.class, tenant.id(), userId);
    }

    /** Signing in moves it too (the membership's last use), so tests read it instead of assuming 0. */
    private long versionOf(TestTenant tenant, UUID userId) {
        return jdbc.queryForObject("select version from tenant_memberships where tenant_id = ? and user_id = ?",
                Long.class, tenant.id(), userId);
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
        TestUser member = memberIn(tenant, "USER");

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
        TestUser member = memberIn(tenant, "USER");

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
        TestUser member = memberIn(tenant, "USER");

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
    @DisplayName("The last ADMIN can't be demoted or removed, even by an ADMIN whose token predates their own demotion: 409 TENANT_MEMBER_LAST_ADMIN")
    void theLastAdminStays() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser second = memberIn(tenant, "ADMIN");
        putAs(second.accessToken(), MEMBERS + "/" + owner.id(), rolePayload("USER", versionOf(tenant, owner.id()))).andExpect(status().isOk());

        // The owner's token still says ADMIN until it expires; second is now the only ADMIN.
        putAs(owner.accessToken(), MEMBERS + "/" + second.id(), rolePayload("USER", versionOf(tenant, second.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_LAST_ADMIN"));
        deleteAs(owner.accessToken(), MEMBERS + "/" + second.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_LAST_ADMIN"));
        assertThat(roleOf(tenant, second.id())).isEqualTo("ADMIN");
        assertThat(adminsOf(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("Two ADMINs demoting each other at once leave exactly one ADMIN: one 200, the other 409 TENANT_MEMBER_LAST_ADMIN")
    void concurrentDemotionsKeepAnAdmin() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser second = memberIn(tenant, "ADMIN");
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
            assertThat(answers).extracting(Answer::status).containsExactlyInAnyOrder(200, 409);
            assertRefusalsAre(answers, 409, "TENANT_MEMBER_LAST_ADMIN");
        }
        assertThat(adminsOf(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("Changing or removing one's own membership is 409 TENANT_MEMBER_OWN_CHANGE")
    void nobodyChangesTheirOwnMembership() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        memberIn(tenant, "ADMIN");

        putAs(owner.accessToken(), MEMBERS + "/" + owner.id(), rolePayload("USER", versionOf(tenant, owner.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_OWN_CHANGE"));
        deleteAs(owner.accessToken(), MEMBERS + "/" + owner.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_OWN_CHANGE"));
        assertThat(roleOf(tenant, owner.id())).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Another tenant's member, or an unknown user, is 404 TENANT_MEMBER_NOT_FOUND and stays as it was")
    void anotherTenantsMemberIs404() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser owner = soleAdminOf(mine);
        TestUser stranger = memberIn(theirs, "USER");

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
    @DisplayName("A USER member is 403 ACCESS_DENIED on every call, with valid bodies; without a token 401")
    void onlyAdminsManageMembers() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = soleAdminOf(tenant);
        TestUser member = memberIn(tenant, "USER");
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
