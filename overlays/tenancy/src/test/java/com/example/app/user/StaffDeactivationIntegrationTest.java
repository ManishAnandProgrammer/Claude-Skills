package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff deactivation: an operator sets {@code platform_users.deactivated_at}; the staff member's tokens die at
 * their next request (ActiveStaffFilter re-reads the row), and they sign in and impersonate no more.
 */
@WithoutTestTenant
class StaffDeactivationIntegrationTest extends IntegrationTest {

    private static final String TENANTS = "/api/v1/platform/tenants";

    @Test
    @DisplayName("""
            A deactivated staff member's token is 401 AUTHENTICATION_REQUIRED at its next request, and their login \
            401 AUTH_INVALID_CREDENTIALS, as for an unknown email; another staff member works on""")
    void aDeactivatedStaffMembersTokenDiesAtTheNextRequest() throws Exception {
        TestStaff leaving = newStaff();
        getAs(leaving.accessToken(), TENANTS).andExpect(status().isOk());

        deactivateStaff(leaving.id());

        getAs(leaving.accessToken(), TENANTS)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        postAs(null, "/api/v1/platform/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(leaving.email(), PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));
        getAs(platformToken(), TENANTS).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A deactivated staff member starts no impersonation: 401, no token")
    void aDeactivatedStaffMemberImpersonatesNobody() throws Exception {
        TestTenant tenant = newTenant();
        TestStaff leaving = newStaff();
        deactivateStaff(leaving.id());

        postAs(leaving.accessToken(), TENANTS + "/" + tenant.id() + "/impersonations", """
                {"reason": "SUP-3"}
                """)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.response.accessToken").doesNotExist());
    }
}
