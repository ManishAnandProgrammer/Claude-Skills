package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The session version with tenancy (the session-version piece): one number per membership, raised by every
 * change of its access, so a member's older tokens are refused on their very next request and the app's
 * refresh reads the access as it is now; another tenant's tokens of the same person stay as they are.
 */
@WithoutTestTenant
class SessionVersionTenancyIntegrationTest extends IntegrationTest {

    private static final String MEMBERS = "/api/v1/tenant-members";
    private static final String ME = "/api/v1/users/me";
    private static final String TENANTS = "/api/v1/platform/tenants";

    /** The access token's claims, read without checking the signature (the server checked it). */
    private static String claim(String accessToken, String jsonPath) {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        Object value = JsonPath.read(payload, jsonPath);
        return String.valueOf(value);
    }

    private static void assertStale(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("SESSION_VERSION_STALE"));
    }

    private ResultActions changeRole(TestUser admin, TestTenant tenant, TestUser member, String role) throws Exception {
        Long version = jdbc.queryForObject("select version from tenant_memberships where tenant_id = ? and user_id = ?",
                Long.class, tenant.id(), member.id());
        return putAs(admin.accessToken(), MEMBERS + "/" + member.id(), """
                {"role": "%s", "version": %d}
                """.formatted(role, version));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken));
    }

    /** One device's tokens. */
    private record Session(String accessToken, String refreshToken) {
    }

    /** The user's tokens for another of their tenants, from their refresh token (which it spends). */
    private Session switchedTo(TestUser user, TestTenant tenant) throws Exception {
        ResultActions switched = postAs(null, "/api/v1/auth/switch-tenant", """
                {"refreshToken": "%s", "tenantId": "%s"}
                """.formatted(user.refreshToken(), tenant.id())).andExpect(status().isOk());
        return new Session(read(switched, "$.response.accessToken"), read(switched, "$.response.refreshToken"));
    }

    private ResultActions changeStatus(TestTenant tenant, String target) throws Exception {
        String staff = platformToken();
        Integer version = read(getAs(staff, TENANTS + "/" + tenant.id()).andExpect(status().isOk()), "$.response.version");
        return patchAs(staff, TENANTS + "/" + tenant.id() + "/status", """
                {"status": "%s", "version": %d}
                """.formatted(target, version));
    }

    @Test
    @DisplayName("""
            A demoted ADMIN's older token is 401 SESSION_VERSION_STALE on the very next request; the refresh returns \
            a USER token, which the ADMIN endpoint refuses with 403""")
    void aDemotionTakesEffectAtOnce() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = newUserIn(tenant, "ADMIN");
        TestUser demoted = newUserIn(tenant, "ADMIN");
        getAs(demoted.accessToken(), MEMBERS).andExpect(status().isOk());

        changeRole(owner, tenant, demoted, "USER").andExpect(status().isOk());

        assertStale(getAs(demoted.accessToken(), MEMBERS));
        String refreshed = read(refresh(demoted.refreshToken()).andExpect(status().isOk()), "$.response.accessToken");
        assertThat(claim(refreshed, "$.roles[0]")).isEqualTo("USER");
        getAs(refreshed, MEMBERS)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        getAs(refreshed, ME).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A promoted member's older token is 401 too; the silent refresh, with no password, returns the ADMIN token")
    void aPromotionRefreshesIntoTheHigherRole() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "USER");
        getAs(member.accessToken(), MEMBERS).andExpect(status().isForbidden());

        changeRole(owner, tenant, member, "ADMIN").andExpect(status().isOk());

        assertStale(getAs(member.accessToken(), ME));
        String refreshed = read(refresh(member.refreshToken()).andExpect(status().isOk()), "$.response.accessToken");
        assertThat(claim(refreshed, "$.roles[0]")).isEqualTo("ADMIN");
        getAs(refreshed, MEMBERS).andExpect(status().isOk());
    }

    @Test
    @DisplayName("""
            A removed member's token is 401 at once and the refresh fails (404 MEMBERSHIP_NOT_FOUND); added again, \
            the old token stays refused (a new membership), and a new sign-in works""")
    void aRemovalTakesEffectAtOnce() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "ADMIN");

        deleteAs(owner.accessToken(), MEMBERS + "/" + member.id()).andExpect(status().isNoContent());

        assertStale(getAs(member.accessToken(), MEMBERS));
        refresh(member.refreshToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MEMBERSHIP_NOT_FOUND"));
        postAs(owner.accessToken(), MEMBERS, """
                {"email": "%s", "role": "USER"}
                """.formatted(member.email())).andExpect(status().isCreated());
        assertStale(getAs(member.accessToken(), MEMBERS));
        getAs(signIn(member).accessToken(), ME).andExpect(status().isOk());
    }

    @Test
    @DisplayName("""
            Suspending a tenant cuts every member's token there at once, one statement for all; their refresh is 403 \
            TENANT_NOT_ACTIVE until it is ACTIVE again, and another tenant's member is untouched""")
    void aSuspensionEndsEveryMembersTokens() throws Exception {
        TestTenant tenant = newTenant();
        TestTenant elsewhere = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "USER");
        TestUser outsider = newUserIn(elsewhere, "USER");

        changeStatus(tenant, "SUSPENDED").andExpect(status().isOk());

        assertStale(getAs(admin.accessToken(), ME));
        assertStale(getAs(member.accessToken(), ME));
        getAs(outsider.accessToken(), ME).andExpect(status().isOk());
        refresh(member.refreshToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_ACTIVE"));
        changeStatus(tenant, "ACTIVE").andExpect(status().isOk());
        String refreshed = read(refresh(member.refreshToken()).andExpect(status().isOk()), "$.response.accessToken");
        getAs(refreshed, ME).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A demotion in tenant A refuses the A token and leaves the same person's B token working")
    void aDemotionInOneTenantLeavesTheOtherAlone() throws Exception {
        TestTenant tenantA = newTenant();
        TestTenant tenantB = newTenant();
        TestUser ownerA = newUserIn(tenantA, "ADMIN");
        TestUser inA = newUserIn(tenantA, "ADMIN");
        addMember(tenantB, inA.id(), "ADMIN");
        Session inB = switchedTo(inA, tenantB);

        changeRole(ownerA, tenantA, inA, "USER").andExpect(status().isOk());

        assertStale(getAs(inA.accessToken(), MEMBERS));
        getAs(inB.accessToken(), MEMBERS).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Signing out everywhere ends the person's sessions in every tenant: both tenants' tokens are 401, both refresh tokens too")
    void signingOutEverywhereEndsEveryTenant() throws Exception {
        TestTenant tenantA = newTenant();
        TestTenant tenantB = newTenant();
        TestUser inA = newUserIn(tenantA, "USER");
        addMember(tenantB, inA.id(), "USER");
        Session inB = switchedTo(signIn(inA), tenantB);

        deleteAs(inA.accessToken(), "/api/v1/users/me/sessions").andExpect(status().isNoContent());

        for (Session session : List.of(new Session(inA.accessToken(), inA.refreshToken()), inB)) {
            assertStale(getAs(session.accessToken(), ME));
            refresh(session.refreshToken())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        }
    }

    @Test
    @DisplayName("Staff impersonating a member carry the member's session version: the member's role change ends the impersonation too")
    void anImpersonationEndsWithTheMembersRoleChange() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "USER");
        String impersonation = read(postAs(platformToken(), TENANTS + "/" + tenant.id() + "/impersonations", """
                {"userId": "%s", "reason": "SUP-2001"}
                """.formatted(member.id())).andExpect(status().isCreated()), "$.response.accessToken");
        assertThat(UUID.fromString(claim(impersonation, "$.membership_id"))).isNotNull();
        getAs(impersonation, ME).andExpect(status().isOk());

        changeRole(owner, tenant, member, "ADMIN").andExpect(status().isOk());

        assertStale(getAs(impersonation, ME));
    }
}
