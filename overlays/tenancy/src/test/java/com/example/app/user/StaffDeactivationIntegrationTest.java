package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff deactivation: an operator sets {@code platform_users.deactivated_at}; the staff member's tokens die at
 * their next request (ActiveStaffFilter re-reads the row): TokenPathIntegrationTest has each path.
 */
@WithoutTestTenant
class StaffDeactivationIntegrationTest extends IntegrationTest {

    private static final String TENANTS = "/api/v1/platform/tenants";

    @Test
    @DisplayName("""
            A deactivated staff member's token worked until then, and another staff member works on \
            (TokenPathIntegrationTest has the deactivated one's paths)""")
    void anotherStaffMemberWorksOn() throws Exception {
        TestStaff leaving = newStaff();
        getAs(leaving.accessToken(), TENANTS).andExpect(status().isOk());

        deactivateStaff(leaving.id());

        getAs(platformToken(), TENANTS).andExpect(status().isOk());
    }
}
