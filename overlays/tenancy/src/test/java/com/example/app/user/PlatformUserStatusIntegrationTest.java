package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithoutTestTenant
class PlatformUserStatusIntegrationTest extends IntegrationTest {

    private static final String SWITCH = "/api/v1/auth/switch-tenant";
    private static final String ACCOUNTS = "/api/v1/platform/users";

    private ResultActions lookUp(String token, String email) throws Exception {
        return mvc.perform(get(ACCOUNTS).param("email", email).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions changeStatus(String token, String email, String status) throws Exception {
        ResultActions found = lookUp(platformToken(), email).andExpect(status().isOk());
        String userId = read(found, "$.response[0].id");
        Number version = read(found, "$.response[0].version");
        return patchAs(token, ACCOUNTS + "/" + userId + "/status", """
                {"status": "%s", "version": %d}
                """.formatted(status, version.longValue()));
    }

    private ResultActions switchTenant(String refreshToken, UUID tenantId) throws Exception {
        return postAs(null, SWITCH, """
                {"refreshToken": "%s", "tenantId": "%s"}
                """.formatted(refreshToken, tenantId));
    }

    // roles:begin
    private String ownerOf(TestTenant tenant) {
        return jdbc.queryForObject("""
                select u.email from tenant_memberships m join users u on u.id = m.user_id
                where m.tenant_id = ? order by m.id limit 1""", String.class, tenant.id());
    }
    // roles:else
    // private String ownerOf(TestTenant tenant) {
    //     return jdbc.queryForObject("""
    //             select u.email from tenant_memberships m join users u on u.id = m.user_id
    //             where m.tenant_id = ? and m.role = 'ADMIN' order by m.id limit 1""", String.class, tenant.id());
    // }
    // roles:end

    private static String tenantOf(String accessToken) {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        return JsonPath.read(payload, "$.tenant_id");
    }

    @Test
    @DisplayName("""
            Staff find an account by its address, ignoring case and surrounding spaces: id, status and version, no \
            role (a role is a membership's); an address nobody has finds an empty list""")
    void staffFindAnAccountByItsAddress() throws Exception {
        TestUser member = newUserIn(newTenant(), "ADMIN");

        lookUp(platformToken(), " " + member.email().toUpperCase(Locale.ROOT) + " ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].id").value(member.id().toString()))
                .andExpect(jsonPath("$.response[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.response[0].version").isNumber())
                .andExpect(jsonPath("$.response[0].role").doesNotExist());
        lookUp(platformToken(), "nobody-" + member.email())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").isEmpty());
    }

    @Test
    @DisplayName("A tenant token can't look up accounts: the platform path is 401 for it, an ADMIN's too")
    void onlyStaffLookUpAnAccount() throws Exception {
        TestUser admin = newUserIn(newTenant(), "ADMIN");

        lookUp(admin.accessToken(), admin.email())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        lookUp(platformToken(), admin.email()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A tenant token can't change an account's status: the platform path is 401 for it, the account unchanged")
    void onlyStaffChangeAnAccountsStatus() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant);

        changeStatus(admin.accessToken(), member.email(), "SUSPENDED").andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, member.id())).isEqualTo("ACTIVE");
    }

    // roles:begin
    @Test
    @DisplayName("""
            The last active member holding for good what manages an open tenant isn't suspended (409 \
            ACCESS_LAST_MANAGER, counted in the tenant's own data); with another one they are, and that one then is \
            the last; one holding it until a date never counts""")
    void aTenantKeepsAnActiveManager() throws Exception {
        TestTenant tenant = newTenant();
        String owner = ownerOf(tenant);
        TestUser expiring = newUserIn(tenant, "ADMIN");
        sqlIn(tenant, "update user_roles set valid_until = now() + interval '30 days' where user_id = ?", expiring.id());

        changeStatus(platformToken(), owner, "SUSPENDED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_LAST_MANAGER"));
        TestUser second = newUserIn(tenant, "ADMIN");
        changeStatus(platformToken(), owner, "SUSPENDED").andExpect(status().isOk());
        changeStatus(platformToken(), second.email(), "SUSPENDED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_LAST_MANAGER"));
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, second.id())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("""
            A SUSPENDED tenant's managers count: a plain member there is suspended, its last manager isn't; a PENDING \
            tenant has no access yet to keep, so its owner is""")
    void aSuspendedTenantsManagersCountAndAPendingOneHasNone() throws Exception {
        TestTenant suspended = newTenant();
        TestUser member = newUserIn(suspended, "USER");
        sql("update tenants set status = 'SUSPENDED' where id = ?", suspended.id());
        TestTenant pending = newTenant();
        sql("update tenants set status = 'PENDING' where id = ?", pending.id());

        changeStatus(platformToken(), member.email(), "SUSPENDED").andExpect(status().isOk());
        changeStatus(platformToken(), ownerOf(suspended), "SUSPENDED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_LAST_MANAGER"));
        changeStatus(platformToken(), ownerOf(pending), "SUSPENDED").andExpect(status().isOk());
    }

    @Test
    @DisplayName("""
            A suspension counts each of the person's tenants in its own data: the last manager of one tenant isn't \
            suspended for being a plain member of another""")
    void aSuspensionCountsEveryTenantOfThePerson() throws Exception {
        TestTenant managed = newTenant();
        TestTenant joined = newTenant();
        TestUser person = newUserIn(joined, "USER");
        addMember(managed, person.id(), "ADMIN");
        String managedOwner = ownerOf(managed);
        changeStatus(platformToken(), managedOwner, "SUSPENDED").andExpect(status().isOk());

        changeStatus(platformToken(), person.email(), "SUSPENDED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_LAST_MANAGER"));
        changeStatus(platformToken(), managedOwner, "ACTIVE").andExpect(status().isOk());
        changeStatus(platformToken(), person.email(), "SUSPENDED").andExpect(status().isOk());
    }

    // roles:else
    // @Test
    // @DisplayName("""
    //         The last ADMIN with an ACTIVE account of an open tenant isn't suspended (409 TENANT_MEMBER_LAST_ADMIN); \
    //         with another active ADMIN they are, and that ADMIN then is the last""")
    // void aTenantKeepsAnActiveAdmin() throws Exception {
    //     TestTenant tenant = newTenant();
    //     String owner = ownerOf(tenant);
    //
    //     changeStatus(platformToken(), owner, "SUSPENDED")
    //             .andExpect(status().isConflict())
    //             .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_LAST_ADMIN"));
    //     TestUser second = newUserIn(tenant, "ADMIN");
    //     changeStatus(platformToken(), owner, "SUSPENDED").andExpect(status().isOk());
    //     changeStatus(platformToken(), second.email(), "SUSPENDED")
    //             .andExpect(status().isConflict())
    //             .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_LAST_ADMIN"));
    // }
    // roles:end
    @Test
    @DisplayName("A CLOSED tenant's only ADMIN may be suspended: a closed tenant needs no manager")
    void aClosedTenantsAdminIsSuspended() throws Exception {
        TestTenant tenant = newTenant();
        String owner = ownerOf(tenant);
        sql("update tenants set status = 'CLOSED' where id = ?", tenant.id());

        changeStatus(platformToken(), owner, "SUSPENDED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("""
            A suspended member's switch-tenant with a refresh token the suspension didn't revoke (one issued in a race) \
            is still refused: 403 AUTH_ACCOUNT_SUSPENDED (TokenPathIntegrationTest has the revoked one)""")
    void aSuspendedMemberSwitchesNowhere() throws Exception {
        TestTenant first = newTenant();
        TestTenant second = newTenant();
        TestUser member = newUserIn(first);
        addMember(second, member.id(), "USER");

        changeStatus(platformToken(), member.email(), "SUSPENDED").andExpect(status().isOk());

        sql("update refresh_tokens set revoked_at = null, revocation_reason = null where user_id = ?", member.id());
        switchTenant(member.refreshToken(), second.id())
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
        // A switch makes the second tenant the most recently used one.
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
