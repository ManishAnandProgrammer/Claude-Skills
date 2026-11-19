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

    // roles:begin
    /** The tenant's built-in ADMIN role given to the member, or taken (the roles piece's assignment endpoint). */
    private ResultActions changeAdmin(TestUser admin, TestTenant tenant, TestUser member, boolean given) throws Exception {
        String roleId = queryIn(tenant, "select id::text from roles where built_in = 'ADMIN'").getFirst().get("id").toString();
        String path = "/api/v1/users/" + member.id() + "/roles/" + roleId;
        return given ? putAs(admin.accessToken(), path, "{}") : deleteAs(admin.accessToken(), path);
    }

    // roles:else
    // private ResultActions changeRole(TestUser admin, TestTenant tenant, TestUser member, String role) throws Exception {
    //     Long version = jdbc.queryForObject("select version from tenant_memberships where tenant_id = ? and user_id = ?",
    //             Long.class, tenant.id(), member.id());
    //     return putAs(admin.accessToken(), MEMBERS + "/" + member.id(), """
    //             {"role": "%s", "version": %d}
    //             """.formatted(role, version));
    // }
    // roles:end
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

    // roles:begin
    // roles:else
    // @Test
    // @DisplayName("""
    //         A demoted ADMIN's refreshed token (TokenPathIntegrationTest has the refused older one) is a USER's: the \
    //         ADMIN endpoint refuses it with 403, the account's own takes it""")
    // void aDemotionRefreshesIntoTheLowerRole() throws Exception {
    //     TestTenant tenant = newTenant();
    //     TestUser owner = newUserIn(tenant, "ADMIN");
    //     TestUser demoted = newUserIn(tenant, "ADMIN");
    //
    //     changeRole(owner, tenant, demoted, "USER").andExpect(status().isOk());
    //
    //     String refreshed = read(refresh(demoted.refreshToken()).andExpect(status().isOk()), "$.response.accessToken");
    //     getAs(refreshed, MEMBERS)
    //             .andExpect(status().isForbidden())
    //             .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    //     getAs(refreshed, ME).andExpect(status().isOk());
    // }
    //
    // @Test
    // @DisplayName("A promoted member's older token is 401 too; the silent refresh, with no password, returns the ADMIN token")
    // void aPromotionRefreshesIntoTheHigherRole() throws Exception {
    //     TestTenant tenant = newTenant();
    //     TestUser owner = newUserIn(tenant, "ADMIN");
    //     TestUser member = newUserIn(tenant, "USER");
    //     getAs(member.accessToken(), MEMBERS).andExpect(status().isForbidden());
    //
    //     changeRole(owner, tenant, member, "ADMIN").andExpect(status().isOk());
    //
    //     assertStale(getAs(member.accessToken(), ME));
    //     String refreshed = read(refresh(member.refreshToken()).andExpect(status().isOk()), "$.response.accessToken");
    //     assertThat(claim(refreshed, "$.roles[0]")).isEqualTo("ADMIN");
    //     getAs(refreshed, MEMBERS).andExpect(status().isOk());
    // }
    // roles:end
    @Test
    @DisplayName("""
            A removed member added again (TokenPathIntegrationTest has the removal's paths): the old token stays \
            refused, a new membership, and a new sign-in works""")
    void aReAddedMembersOldTokenStaysRefused() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "ADMIN");

        deleteAs(owner.accessToken(), MEMBERS + "/" + member.id()).andExpect(status().isNoContent());

        // roles:begin
        postAs(owner.accessToken(), MEMBERS, """
                {"email": "%s"}
                """.formatted(member.email())).andExpect(status().isCreated());
        // roles:else
        // postAs(owner.accessToken(), MEMBERS, """
        //         {"email": "%s", "role": "USER"}
        //         """.formatted(member.email())).andExpect(status().isCreated());
        // roles:end
        assertStale(getAs(member.accessToken(), MEMBERS));
        getAs(signIn(member).accessToken(), ME).andExpect(status().isOk());
    }

    @Test
    @DisplayName("""
            Suspending a tenant cuts every member's token there (TokenPathIntegrationTest has each path), the ADMIN's \
            too; another tenant's member is untouched, and once it is ACTIVE again the refresh works""")
    void aSuspensionEndsEveryMembersTokens() throws Exception {
        TestTenant tenant = newTenant();
        TestTenant elsewhere = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "USER");
        TestUser outsider = newUserIn(elsewhere, "USER");

        changeStatus(tenant, "SUSPENDED").andExpect(status().isOk());

        assertStale(getAs(admin.accessToken(), ME));
        getAs(outsider.accessToken(), ME).andExpect(status().isOk());
        changeStatus(tenant, "ACTIVE").andExpect(status().isOk());
        String refreshed = read(refresh(member.refreshToken()).andExpect(status().isOk()), "$.response.accessToken");
        getAs(refreshed, ME).andExpect(status().isOk());
    }

    // roles:begin
    // roles:else
    // @Test
    // @DisplayName("A demotion in tenant A refuses the A token and leaves the same person's B token working")
    // void aDemotionInOneTenantLeavesTheOtherAlone() throws Exception {
    //     TestTenant tenantA = newTenant();
    //     TestTenant tenantB = newTenant();
    //     TestUser ownerA = newUserIn(tenantA, "ADMIN");
    //     TestUser inA = newUserIn(tenantA, "ADMIN");
    //     addMember(tenantB, inA.id(), "ADMIN");
    //     Session inB = switchedTo(inA, tenantB);
    //
    //     changeRole(ownerA, tenantA, inA, "USER").andExpect(status().isOk());
    //
    //     assertStale(getAs(inA.accessToken(), MEMBERS));
    //     getAs(inB.accessToken(), MEMBERS).andExpect(status().isOk());
    // }
    // roles:end
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

    // roles:begin
    @Test
    @DisplayName("""
            With the roles piece a role given or taken needs no new session: the member's same token manages the \
            members on its next request, and stops once it is taken; the person's token in another tenant is untouched""")
    void aRoleChangeReachesTheSameToken() throws Exception {
        TestTenant tenantA = newTenant();
        TestTenant tenantB = newTenant();
        TestUser ownerA = newUserIn(tenantA, "ADMIN");
        TestUser inA = newUserIn(tenantA, "USER");
        addMember(tenantB, inA.id(), "ADMIN");
        Session inB = switchedTo(inA, tenantB);
        getAs(inA.accessToken(), MEMBERS).andExpect(status().isForbidden());

        changeAdmin(ownerA, tenantA, inA, true).andExpect(status().isOk());
        getAs(inA.accessToken(), MEMBERS).andExpect(status().isOk());
        changeAdmin(ownerA, tenantA, inA, false).andExpect(status().isNoContent());

        getAs(inA.accessToken(), MEMBERS)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        getAs(inB.accessToken(), MEMBERS).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Staff impersonating a member carry the member's session version: the member's removal ends the impersonation too")
    void anImpersonationEndsWithTheMembersRemoval() throws Exception {
        TestTenant tenant = newTenant();
        TestUser owner = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "USER");
        String impersonation = read(postAs(platformToken(), TENANTS + "/" + tenant.id() + "/impersonations", """
                {"userId": "%s", "reason": "SUP-2001"}
                """.formatted(member.id())).andExpect(status().isCreated()), "$.response.accessToken");
        assertThat(UUID.fromString(claim(impersonation, "$.membership_id"))).isNotNull();
        getAs(impersonation, ME).andExpect(status().isOk());

        deleteAs(owner.accessToken(), MEMBERS + "/" + member.id()).andExpect(status().isNoContent());

        assertStale(getAs(impersonation, ME));
    }
    // roles:else
    // @Test
    // @DisplayName("Staff impersonating a member carry the member's session version: the member's role change ends the impersonation too")
    // void anImpersonationEndsWithTheMembersRoleChange() throws Exception {
    //     TestTenant tenant = newTenant();
    //     TestUser owner = newUserIn(tenant, "ADMIN");
    //     TestUser member = newUserIn(tenant, "USER");
    //     String impersonation = read(postAs(platformToken(), TENANTS + "/" + tenant.id() + "/impersonations", """
    //             {"userId": "%s", "reason": "SUP-2001"}
    //             """.formatted(member.id())).andExpect(status().isCreated()), "$.response.accessToken");
    //     assertThat(UUID.fromString(claim(impersonation, "$.membership_id"))).isNotNull();
    //     getAs(impersonation, ME).andExpect(status().isOk());
    //
    //     changeRole(owner, tenant, member, "ADMIN").andExpect(status().isOk());
    //
    //     assertStale(getAs(impersonation, ME));
    // }
    // roles:end

}
