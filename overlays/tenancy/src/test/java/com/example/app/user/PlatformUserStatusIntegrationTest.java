package com.example.app.user;

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
 * The account lifecycle with tenancy: platform staff suspend accounts (global, every tenant at once), a tenant
 * keeps an ACTIVE ADMIN, a suspended member gets no token from switch-tenant or impersonation, and a password
 * change stays in the calling device's tenant. The rest of the lifecycle is AccountLifecycleIntegrationTest's.
 */
@WithoutTestTenant
class PlatformUserStatusIntegrationTest extends IntegrationTest {

    private static final String SWITCH = "/api/v1/auth/switch-tenant";

    private long versionOf(UUID userId) {
        return jdbc.queryForObject("select version from users where id = ?", Long.class, userId);
    }

    private ResultActions changeStatus(String token, UUID userId, String status) throws Exception {
        return patchAs(token, "/api/v1/platform/users/" + userId + "/status", """
                {"status": "%s", "version": %d}
                """.formatted(status, versionOf(userId)));
    }

    private ResultActions switchTenant(String refreshToken, UUID tenantId) throws Exception {
        return postAs(null, SWITCH, """
                {"refreshToken": "%s", "tenantId": "%s"}
                """.formatted(refreshToken, tenantId));
    }

    private UUID ownerOf(TestTenant tenant) {
        return jdbc.queryForObject("select user_id from tenant_memberships where tenant_id = ? and role = 'ADMIN' order by id limit 1",
                UUID.class, tenant.id());
    }

    private static String tenantOf(String accessToken) {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        return JsonPath.read(payload, "$.tenant_id");
    }

    @Test
    @DisplayName("A tenant token can't change an account's status: the platform path is 401 for it, the account unchanged")
    void onlyStaffChangeAnAccountsStatus() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant);

        changeStatus(admin.accessToken(), member.id(), "SUSPENDED").andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, member.id())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("""
            The last ADMIN with an ACTIVE account of an open tenant isn't suspended (409 TENANT_MEMBER_LAST_ADMIN); \
            with another active ADMIN they are, and that ADMIN then is the last""")
    void aTenantKeepsAnActiveAdmin() throws Exception {
        TestTenant tenant = newTenant();
        UUID owner = ownerOf(tenant);

        changeStatus(platformToken(), owner, "SUSPENDED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_LAST_ADMIN"));
        TestUser second = newUserIn(tenant, "ADMIN");
        changeStatus(platformToken(), owner, "SUSPENDED").andExpect(status().isOk());
        changeStatus(platformToken(), second.id(), "SUSPENDED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_LAST_ADMIN"));
    }

    @Test
    @DisplayName("A CLOSED tenant's only ADMIN may be suspended: a closed tenant needs no manager")
    void aClosedTenantsAdminIsSuspended() throws Exception {
        TestTenant tenant = newTenant();
        UUID owner = ownerOf(tenant);
        sql("update tenants set status = 'CLOSED' where id = ?", tenant.id());

        changeStatus(platformToken(), owner, "SUSPENDED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("""
            A suspended member's switch-tenant is refused: 401 with the revoked refresh token, 403 \
            AUTH_ACCOUNT_SUSPENDED with one the suspension didn't revoke""")
    void aSuspendedMemberSwitchesNowhere() throws Exception {
        TestTenant first = newTenant();
        TestTenant second = newTenant();
        TestUser member = newUserIn(first);
        addMember(second, member.id(), "USER");

        changeStatus(platformToken(), member.id(), "SUSPENDED").andExpect(status().isOk());

        switchTenant(member.refreshToken(), second.id())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        sql("update refresh_tokens set revoked_at = null, revocation_reason = null where user_id = ?", member.id());
        switchTenant(member.refreshToken(), second.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_SUSPENDED"));
    }

    @Test
    @DisplayName("Staff can't act as a suspended member: impersonation is 403 AUTH_ACCOUNT_SUSPENDED")
    void aSuspendedMemberIsNotImpersonated() throws Exception {
        TestTenant tenant = newTenant();
        TestUser member = newUserIn(tenant);
        changeStatus(platformToken(), member.id(), "SUSPENDED").andExpect(status().isOk());

        postAs(platformToken(), "/api/v1/platform/tenants/" + tenant.id() + "/impersonations", """
                {"userId": "%s", "reason": "SUP-1"}
                """.formatted(member.id()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_SUSPENDED"));
    }

    @Test
    @DisplayName("A password change answers tokens for the tenant the device is signed in to, not the most recently used one")
    void aPasswordChangeStaysInTheDevicesTenant() throws Exception {
        TestTenant first = newTenant();
        TestTenant second = newTenant();
        TestUser phone = newUserIn(first);
        addMember(second, phone.id(), "USER");
        // The laptop moves to the second tenant, which becomes the most recently used one.
        TestUser laptop = signIn(phone);
        switchTenant(laptop.refreshToken(), second.id()).andExpect(status().isOk());

        String changed = read(postAs(phone.accessToken(), "/api/v1/users/me/password", """
                {"currentPassword": "%s", "newPassword": "Battery-Staple-7"}
                """.formatted(PASSWORD)).andExpect(status().isOk()), "$.response.accessToken");

        assertThat(tenantOf(changed)).isEqualTo(first.id().toString());
    }

    @Test
    @DisplayName("Staff acting as a member never change the member's password: 403 ACCESS_DENIED, the password unchanged")
    void anImpersonationChangesNoPassword() throws Exception {
        TestTenant tenant = newTenant();
        TestUser member = newUserIn(tenant);
        String impersonation = read(postAs(platformToken(), "/api/v1/platform/tenants/" + tenant.id() + "/impersonations", """
                {"userId": "%s", "reason": "SUP-2"}
                """.formatted(member.id())).andExpect(status().isCreated()), "$.response.accessToken");

        postAs(impersonation, "/api/v1/users/me/password", """
                {"currentPassword": "%s", "newPassword": "Battery-Staple-7"}
                """.formatted(PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        login(member.email(), PASSWORD).andExpect(status().isOk());
    }
}
