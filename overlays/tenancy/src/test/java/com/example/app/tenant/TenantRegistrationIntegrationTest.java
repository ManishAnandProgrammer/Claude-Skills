package com.example.app.tenant;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Self-registration: a PENDING tenant that staff approve; the answer never tells whether an email has an account. */
@WithoutTestTenant
class TenantRegistrationIntegrationTest extends IntegrationTest {

    private static final String REGISTRATIONS = "/api/v1/auth/tenant-registrations";

    private ResultActions register(String organisation, String email, String password) throws Exception {
        return postAs(null, REGISTRATIONS, """
                {"organisationName": "%s", "email": "%s", "fullName": "Owner", "password": "%s"}
                """.formatted(organisation, email, password));
    }

    private UUID tenantNamed(String name) {
        return jdbc.queryForObject("select id from tenants where name = ?", UUID.class, name);
    }

    private int tenantsNamed(String name) {
        return jdbc.queryForObject("select count(*) from tenants where name = ?", Integer.class, name);
    }

    @Test
    @DisplayName("A new email gets an account owning a PENDING tenant; login waits for approval, then works")
    void registrationWaitsForApproval() throws Exception {
        String email = uniqueEmail("founder");
        String name = "Registered " + UUID.randomUUID();

        register(name, email, PASSWORD).andExpect(status().isAccepted()).andExpect(content().string(""));

        UUID tenantId = tenantNamed(name);
        assertThat(jdbc.queryForObject("select status from tenants where id = ?", String.class, tenantId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                select source from tenant_status_changes where tenant_id = ? and to_status = 'PENDING'
                """, String.class, tenantId)).isEqualTo("REGISTRATION");
        login(email, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.tenantStatus").value("PENDING"));
        // Not approved: provisioning leaves it alone.
        provisionApprovedTenants();
        login(email, PASSWORD).andExpect(status().isForbidden());

        // The answer is the tenant as provisioning left it: row mode activated it before the answer.
        postAs(platformToken(), "/api/v1/platform/tenants/" + tenantId + "/approve", "")
                .andExpect(status().isAccepted())
                // tenancy-row:begin
                .andExpect(jsonPath("$.response.status").value("ACTIVE"))
                .andExpect(jsonPath("$.response.version").value(1));
                // tenancy-row:else
                // .andExpect(jsonPath("$.response.status").value("PENDING"));
                // tenancy-row:end
        provisionApprovedTenants();

        ResultActions signedIn = login(email, PASSWORD).andExpect(status().isOk());
        getAs(read(signedIn, "$.response.accessToken"), "/api/v1/users/me/tenants")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].tenantId").value(tenantId.toString()))
                // roles:begin
                .andExpect(jsonPath("$.response[0].role").doesNotExist())
                // roles:else
                // .andExpect(jsonPath("$.response[0].role").value("ADMIN"))
                // roles:end
                .andExpect(jsonPath("$.response[0].tenantStatus").value("ACTIVE"));
        postAs(platformToken(), "/api/v1/platform/tenants/" + tenantId + "/approve", "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_STATUS_CHANGE_INVALID"));
    }

    @Test
    @DisplayName("An existing account registers with its own password; a wrong one registers nothing, with the same answer")
    void existingAccountsNeedTheirPassword() throws Exception {
        TestUser user = newUser();
        String mine = "Mine " + UUID.randomUUID();
        String foreign = "Foreign " + UUID.randomUUID();

        register(mine, user.email(), PASSWORD).andExpect(status().isAccepted()).andExpect(content().string(""));
        register(foreign, user.email(), "Wrong-Horse-9").andExpect(status().isAccepted()).andExpect(content().string(""));

        UUID tenantId = tenantNamed(mine);
        // roles:begin
        // Its owner's membership; their ADMIN role is the tenant's own data, given at its provisioning.
        assertThat(jdbc.queryForObject("select count(*) from tenant_memberships where tenant_id = ? and user_id = ?",
                Integer.class, tenantId, user.id())).isOne();
        // roles:else
        // assertThat(jdbc.queryForObject("select role from tenant_memberships where tenant_id = ? and user_id = ?",
        //         String.class, tenantId, user.id())).isEqualTo("ADMIN");
        // roles:end
        assertThat(tenantsNamed(foreign)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, user.email())).isOne();
    }

    @Test
    @DisplayName("An invalid registration is 400 with the field named")
    void invalidRegistrationIs400() throws Exception {
        register("", uniqueEmail("founder"), PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/organisationName"));
    }
}
