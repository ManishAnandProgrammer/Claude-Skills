package com.example.app.tenant;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two planes and the tenant at token issue: a platform token is refused on every tenant endpoint
 * and a tenant token under {@code /platform/**}; tokens are issued only for an ACTIVE tenant the user
 * belongs to.
 */
@WithoutTestTenant
class TenantPlanesIntegrationTest extends IntegrationTest {

    private static final String TENANTS = "/api/v1/platform/tenants";
    private static final String SWITCH = "/api/v1/auth/switch-tenant";

    /** The access token's claims, read without checking the signature (the server checked it). */
    private static String claim(String accessToken, String jsonPath) {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        Object value = JsonPath.read(payload, jsonPath);
        return String.valueOf(value);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken));
    }

    private ResultActions switchTenant(String refreshToken, UUID tenantId) throws Exception {
        return postAs(null, SWITCH, """
                {"refreshToken": "%s", "tenantId": "%s"}
                """.formatted(refreshToken, tenantId));
    }

    private void changeStatus(UUID tenantId, String status) throws Exception {
        String staff = platformToken();
        Integer version = read(getAs(staff, TENANTS + "/" + tenantId), "$.response.version");
        patchAs(staff, TENANTS + "/" + tenantId + "/status", """
                {"status": "%s", "version": %d}
                """.formatted(status, version)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A platform token on a tenant endpoint is 401 AUTHENTICATION_REQUIRED")
    void platformTokenIsRefusedOnTheTenantPlane() throws Exception {
        getAs(platformToken(), "/api/v1/users/me")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("A tenant token, or none, under /platform/** is 401 AUTHENTICATION_REQUIRED")
    void tenantTokenIsRefusedOnThePlatformPlane() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), TENANTS)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(null, TENANTS)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("A tenant token names its tenant, the membership's role and the tenant audience")
    void tokensCarryTheTenant() throws Exception {
        TestTenant tenant = newTenant();
        TestUser user = newUserIn(tenant);

        assertThat(claim(user.accessToken(), "$.tenant_id")).isEqualTo(tenant.id().toString());
        assertThat(claim(user.accessToken(), "$.roles[0]")).isEqualTo("USER");
        assertThat(claim(user.accessToken(), "$.aud")).contains("tenant");
    }

    @Test
    @DisplayName("A suspended tenant's login and refresh are 403 TENANT_NOT_ACTIVE naming the status; reactivated, both work")
    void suspendedTenantGetsNoTokens() throws Exception {
        TestTenant tenant = newTenant();
        TestUser user = newUserIn(tenant);
        changeStatus(tenant.id(), "SUSPENDED");

        login(user.email(), PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.tenantStatus").value("SUSPENDED"));
        refresh(user.refreshToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_ACTIVE"));

        changeStatus(tenant.id(), "ACTIVE");
        login(user.email(), PASSWORD).andExpect(status().isOk());
        refresh(user.refreshToken()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Login skips a suspended most recent tenant for another ACTIVE one; switching to the suspended one is 403 and keeps the session")
    void suspendedTenantDoesNotLockOutTheOthers() throws Exception {
        TestTenant other = newTenant();
        TestTenant suspended = newTenant();
        TestUser user = newUserIn(other);
        addMember(suspended, user.id(), "ADMIN");
        String refreshToken = read(switchTenant(user.refreshToken(), suspended.id()).andExpect(status().isOk()),
                "$.response.refreshToken");
        changeStatus(suspended.id(), "SUSPENDED");

        String loggedIn = read(login(user.email(), PASSWORD).andExpect(status().isOk()), "$.response.accessToken");
        assertThat(claim(loggedIn, "$.tenant_id")).isEqualTo(other.id().toString());
        String otherRefresh = read(switchTenant(refreshToken, other.id()).andExpect(status().isOk()), "$.response.refreshToken");
        switchTenant(otherRefresh, suspended.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.tenantStatus").value("SUSPENDED"));
        refresh(otherRefresh).andExpect(status().isOk());
    }

    @Test
    @DisplayName("An account that belongs to no tenant can't sign in: 403 TENANT_MEMBERSHIP_REQUIRED")
    void accountWithoutMembershipIsRefused() throws Exception {
        String email = uniqueEmail("lonely");
        rawSignup(email, PASSWORD).andExpect(status().isCreated());

        login(email, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBERSHIP_REQUIRED"));
    }

    @Test
    @DisplayName("Refresh and switch-tenant read the membership's role at the issue, never the presented token's: a role change reaches the next one")
    void tokenIssuesReadTheCurrentRole() throws Exception {
        TestTenant first = newTenant();
        TestTenant second = newTenant();
        TestUser user = newUserIn(first);
        addMember(second, user.id(), "USER");
        sql("update tenant_memberships set role = 'ADMIN' where tenant_id = ? and user_id = ?", first.id(), user.id());
        sql("update tenant_memberships set role = 'ADMIN' where tenant_id = ? and user_id = ?", second.id(), user.id());

        ResultActions refreshed = refresh(user.refreshToken()).andExpect(status().isOk());
        assertThat(claim(read(refreshed, "$.response.accessToken"), "$.roles[0]")).isEqualTo("ADMIN");
        ResultActions switched = switchTenant(read(refreshed, "$.response.refreshToken"), second.id()).andExpect(status().isOk());
        assertThat(claim(read(switched, "$.response.accessToken"), "$.roles[0]")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Switch-tenant issues tokens for another membership with its role; login then picks it as the most recent")
    void switchTenantMovesToAnotherMembership() throws Exception {
        TestTenant first = newTenant();
        TestTenant second = newTenant();
        TestUser user = newUserIn(first);
        addMember(second, user.id(), "ADMIN");

        ResultActions switched = switchTenant(user.refreshToken(), second.id()).andExpect(status().isOk());
        String accessToken = read(switched, "$.response.accessToken");
        assertThat(claim(accessToken, "$.tenant_id")).isEqualTo(second.id().toString());
        assertThat(claim(accessToken, "$.roles[0]")).isEqualTo("ADMIN");
        refresh(user.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));

        String loggedIn = read(login(user.email(), PASSWORD).andExpect(status().isOk()), "$.response.accessToken");
        assertThat(claim(loggedIn, "$.tenant_id")).isEqualTo(second.id().toString());
    }

    @Test
    @DisplayName("Switching to a tenant the user isn't in, or to none, is 404 MEMBERSHIP_NOT_FOUND; the refresh token stays usable")
    void switchToAForeignTenantIs404() throws Exception {
        TestTenant foreign = newTenant();
        TestUser user = newUser();

        switchTenant(user.refreshToken(), foreign.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MEMBERSHIP_NOT_FOUND"));
        switchTenant(user.refreshToken(), UUID.randomUUID()).andExpect(status().isNotFound());
        refresh(user.refreshToken()).andExpect(status().isOk());
    }
}
