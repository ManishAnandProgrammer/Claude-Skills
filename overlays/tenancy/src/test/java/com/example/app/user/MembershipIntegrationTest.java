package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "My tenants": the signed-in user's memberships, the ids switch-tenant takes. */
@WithoutTestTenant
class MembershipIntegrationTest extends IntegrationTest {

    private static final String MY_TENANTS = "/api/v1/users/me/tenants";

    @Test
    @DisplayName("Lists every tenant the user belongs to with the role there, the token's own marked current")
    void listsTheUsersTenants() throws Exception {
        TestTenant first = newTenant();
        TestTenant second = newTenant();
        TestUser user = newUserIn(first);
        addMember(second, user.id(), "ADMIN");

        getAs(user.accessToken(), MY_TENANTS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", hasSize(2)))
                .andExpect(jsonPath("$.response[0].tenantId").value(first.id().toString()))
                .andExpect(jsonPath("$.response[0].role").value("USER"))
                .andExpect(jsonPath("$.response[0].current").value(true))
                .andExpect(jsonPath("$.response[0].tenantName").isNotEmpty())
                .andExpect(jsonPath("$.response[1].tenantId").value(second.id().toString()))
                .andExpect(jsonPath("$.response[1].role").value("ADMIN"))
                .andExpect(jsonPath("$.response[1].current").value(false))
                .andExpect(jsonPath("$.response[1].tenantStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("Without a token it is 401; a platform token is 401 too")
    void needsATenantToken() throws Exception {
        getAs(null, MY_TENANTS).andExpect(status().isUnauthorized());
        getAs(platformToken(), MY_TENANTS).andExpect(status().isUnauthorized());
    }
}
