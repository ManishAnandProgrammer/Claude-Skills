package com.example.app.tenant;

import com.example.app.common.security.CurrentUser;
import com.example.app.support.IntegrationTest;
import com.example.app.support.SupportAccessPolicies;
import com.example.app.support.WithoutTestTenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithoutTestTenant
class ImpersonationIntegrationTest extends IntegrationTest {

    @Autowired
    private CurrentUser currentUser;

    private static String claim(String accessToken, String jsonPath) {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        Object value = JsonPath.read(payload, jsonPath);
        return String.valueOf(value);
    }

    private ResultActions impersonate(UUID tenantId, String body) throws Exception {
        return postAs(platformToken(), "/api/v1/platform/tenants/" + tenantId + "/impersonations", body);
    }

    // roles:begin
    private UUID ownerOf(TestTenant tenant) {
        return jdbc.queryForObject("select user_id from tenant_memberships where tenant_id = ? order by id limit 1",
                UUID.class, tenant.id());
    }

    private List<String> permissionsOf(String accessToken) throws Exception {
        return read(getAs(accessToken, "/api/v1/users/me/access").andExpect(status().isOk()), "$.response.permissions");
    }
    // roles:else
    // private UUID ownerOf(TestTenant tenant) {
    //     return jdbc.queryForObject("""
    //             select user_id from tenant_memberships where tenant_id = ? and role = 'ADMIN' order by id limit 1
    //             """, UUID.class, tenant.id());
    // }
    // roles:end

    private int auditRows(TestTenant tenant, String detail) {
        Integer rows = jdbc.queryForObject("""
                select count(*) from platform_audit where tenant_id = ? and action = 'IMPERSONATION' and detail like ?
                """, Integer.class, tenant.id(), "%" + detail + "%");
        return rows;
    }

    @Test
    @DisplayName("Staff get a tenant token for the tenant's ADMIN, with themselves as actor; the tenant plane takes it")
    void impersonationTokenWorksOnTheTenantPlane() throws Exception {
        TestTenant tenant = newTenant();
        UUID owner = ownerOf(tenant);

        String token = read(impersonate(tenant.id(), """
                {"reason": "SUP-1234"}
                """).andExpect(status().isCreated()), "$.response.accessToken");

        assertThat(claim(token, "$.tenant_id")).isEqualTo(tenant.id().toString());
        assertThat(claim(token, "$.sub")).isEqualTo(owner.toString());
        // roles:begin
        assertThat(permissionsOf(token)).contains("TENANT_MEMBERS_MANAGE", "ROLES_MANAGE", "ACCESS_GRANT");
        // roles:else
        // assertThat(claim(token, "$.roles[0]")).isEqualTo("ADMIN");
        // roles:end
        assertThat(claim(token, "$.act.sub")).isNotEmpty().isNotEqualTo(owner.toString());
        getAs(token, "/api/v1/users/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.id").value(owner.toString()));
        assertThat(auditRows(tenant, "SUP-1234")).isOne();
    }

    @Test
    @DisplayName("A named member is impersonated with their own role; a user outside the tenant is 404 MEMBERSHIP_NOT_FOUND")
    void impersonateANamedMember() throws Exception {
        TestTenant tenant = newTenant();
        TestUser member = newUserIn(tenant);

        String token = read(impersonate(tenant.id(), """
                {"userId": "%s", "reason": "SUP-1235"}
                """.formatted(member.id())).andExpect(status().isCreated()), "$.response.accessToken");
        assertThat(claim(token, "$.sub")).isEqualTo(member.id().toString());
        // roles:begin
        assertThat(permissionsOf(token)).doesNotContain("TENANT_MEMBERS_MANAGE");
        // roles:else
        // assertThat(claim(token, "$.roles[0]")).isEqualTo("USER");
        // roles:end

        impersonate(tenant.id(), """
                {"userId": "%s", "reason": "SUP-1235"}
                """.formatted(newUser().id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MEMBERSHIP_NOT_FOUND"));
    }

    // roles:begin
    @Test
    @DisplayName("""
            By default staff act as the oldest member who manages the tenant's members for good: once the owner holds \
            USER alone, a later ADMIN""")
    void theDefaultMemberManagesTheMembers() throws Exception {
        TestTenant tenant = newTenant();
        UUID owner = ownerOf(tenant);
        TestUser later = newUserIn(tenant, "ADMIN");
        TestUser latest = newUserIn(tenant, "ADMIN");
        sqlIn(tenant, "delete from user_roles where user_id = ?", owner);
        giveBuiltInRole(tenant, owner, "USER");

        String token = read(impersonate(tenant.id(), """
                {"reason": "SUP-1236"}
                """).andExpect(status().isCreated()), "$.response.accessToken");

        assertThat(claim(token, "$.sub")).isEqualTo(later.id().toString()).isNotEqualTo(latest.id().toString());
    }

    // roles:end
    @Test
    @DisplayName("Without a reason it is 400 VALIDATION_FAILED naming reason: every token issued is on record with one")
    void reasonIsRequired() throws Exception {
        TestTenant tenant = newTenant();

        for (String body : List.of("{}", """
                {"reason": "  "}
                """)) {
            impersonate(tenant.id(), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].pointer").value("#/reason"));
        }
    }

    @Test
    @DisplayName("A policy that refuses blocks the token: 403 SUPPORT_ACCESS_DENIED, and the attempt is still audited")
    void refusingPolicyBlocksIssuance() throws Exception {
        TestTenant tenant = newTenant();
        SupportAccessPolicies.WITHOUT_CONSENT.add(tenant.id());
        try {
            impersonate(tenant.id(), """
                    {"reason": "SUP-9999"}
                    """)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("SUPPORT_ACCESS_DENIED"))
                    .andExpect(jsonPath("$.response").doesNotExist());
        } finally {
            SupportAccessPolicies.WITHOUT_CONSENT.remove(tenant.id());
        }

        assertThat(auditRows(tenant, "SUP-9999")).isOne();
    }

    @Test
    @DisplayName("CurrentUser names the actor of an impersonation token, and none on the user's own")
    void currentUserExposesTheActor() {
        UUID staff = UUID.randomUUID();
        Jwt impersonation = Jwt.withTokenValue("test").header("alg", "none").subject(UUID.randomUUID().toString())
                .claim(CurrentUser.ACTOR_CLAIM, Map.of(CurrentUser.ACTOR_SUBJECT, staff.toString())).build();
        Jwt own = Jwt.withTokenValue("test").header("alg", "none").subject(UUID.randomUUID().toString()).build();
        try {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(impersonation));
            assertThat(currentUser.actorId()).contains(staff);
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(own));
            assertThat(currentUser.actorId()).isEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
