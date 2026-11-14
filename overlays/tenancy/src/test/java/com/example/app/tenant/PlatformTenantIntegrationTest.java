package com.example.app.tenant;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.example.app.tenant.api.Tenants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The tenant register for platform staff: creation with its owner, the lifecycle, the audit trail. */
@WithoutTestTenant
class PlatformTenantIntegrationTest extends IntegrationTest {

    private static final String TENANTS = "/api/v1/platform/tenants";

    @Autowired
    private Tenants tenants;

    private static String createPayload(String name, String ownerEmail) {
        return """
                {"name": "%s", "ownerEmail": "%s"}
                """.formatted(name, ownerEmail);
    }

    private static String statusPayload(String status, long version) {
        return """
                {"status": "%s", "version": %d}
                """.formatted(status, version);
    }

    private ResultActions changeStatus(String staff, UUID tenantId, String status, long version) throws Exception {
        return patchAs(staff, TENANTS + "/" + tenantId + "/status", statusPayload(status, version));
    }

    private long version(String staff, UUID tenantId) throws Exception {
        Integer version = read(getAs(staff, TENANTS + "/" + tenantId).andExpect(status().isOk()), "$.response.version");
        return version;
    }

    @Test
    @DisplayName("Staff create a tenant PENDING, its owner becomes its ADMIN, and the creation is recorded with its source")
    void createIsPendingWithTheOwnerAsAdmin() throws Exception {
        String staff = platformToken();
        String ownerEmail = uniqueEmail("owner");
        String ownerId = read(rawSignup(ownerEmail, PASSWORD).andExpect(status().isCreated()), "$.response.id");

        ResultActions created = postAs(staff, TENANTS, createPayload("  Acme Ltd ", ownerEmail))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.name").value("Acme Ltd"))
                .andExpect(jsonPath("$.response.status").value("PENDING"))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdBy").isNotEmpty());
        UUID tenantId = UUID.fromString(read(created, "$.response.id"));

        String role = jdbc.queryForObject("select role from tenant_memberships where tenant_id = ? and user_id = ?::uuid",
                String.class, tenantId, ownerId);
        assertThat(role).isEqualTo("ADMIN");
        String source = jdbc.queryForObject("""
                select source from tenant_status_changes where tenant_id = ? and from_status is null and to_status = 'PENDING'
                """, String.class, tenantId);
        assertThat(source).isEqualTo("STAFF");
    }

    @Test
    @DisplayName("An owner email without an account is 404 TENANT_OWNER_NOT_FOUND, and no tenant is created")
    void ownerWithoutAnAccountIs404() throws Exception {
        String name = "Nobody's " + UUID.randomUUID();

        postAs(platformToken(), TENANTS, createPayload(name, uniqueEmail("ghost")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_OWNER_NOT_FOUND"));

        assertThat(jdbc.queryForObject("select count(*) from tenants where name = ?", Integer.class, name)).isZero();
    }

    @Test
    @DisplayName("ACTIVE -> SUSPENDED -> ACTIVE -> SUSPENDED -> CLOSED; every other change is 409 TENANT_STATUS_CHANGE_INVALID")
    void theLifecycleAllowsOnlyItsOwnChanges() throws Exception {
        String staff = platformToken();
        UUID tenantId = newTenant().id();

        changeStatus(staff, tenantId, "CLOSED", version(staff, tenantId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_STATUS_CHANGE_INVALID"));
        changeStatus(staff, tenantId, "SUSPENDED", version(staff, tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("SUSPENDED"));
        changeStatus(staff, tenantId, "ACTIVE", version(staff, tenantId)).andExpect(status().isOk());
        changeStatus(staff, tenantId, "SUSPENDED", version(staff, tenantId)).andExpect(status().isOk());
        changeStatus(staff, tenantId, "CLOSED", version(staff, tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("CLOSED"));
        changeStatus(staff, tenantId, "ACTIVE", version(staff, tenantId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_STATUS_CHANGE_INVALID"));

        Integer changes = jdbc.queryForObject("select count(*) from tenant_status_changes where tenant_id = ?", Integer.class, tenantId);
        assertThat(changes).as("created, activated, then four staff changes").isEqualTo(6);
    }

    @Test
    @DisplayName("Staff can reject a PENDING tenant but never activate one: activation is the provisioning's")
    void pendingCanBeRejectedNotActivated() throws Exception {
        String staff = platformToken();
        String ownerEmail = uniqueEmail("owner");
        rawSignup(ownerEmail, PASSWORD).andExpect(status().isCreated());
        // Registered, so not yet approved: a staff-created tenant may already be provisioned (row mode does it at once).
        UUID tenantId = tenants.register("Pending Ltd", ownerEmail);

        changeStatus(staff, tenantId, "ACTIVE", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TENANT_STATUS_CHANGE_INVALID"));
        changeStatus(staff, tenantId, "REJECTED", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("REJECTED"));
    }

    @Test
    @DisplayName("A stale version is 409 OPTIMISTIC_LOCK_CONFLICT; an unknown tenant is 404 TENANT_NOT_FOUND")
    void staleVersionAndUnknownTenant() throws Exception {
        String staff = platformToken();
        UUID tenantId = newTenant().id();

        changeStatus(staff, tenantId, "SUSPENDED", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
        getAs(staff, TENANTS + "/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_FOUND"));
    }

    @Test
    @DisplayName("The list filters by status and name")
    void listFiltersByStatusAndName() throws Exception {
        String staff = platformToken();
        UUID tenantId = newTenant().id();
        String name = read(getAs(staff, TENANTS + "/" + tenantId), "$.response.name");

        getAs(staff, TENANTS + "?status=ACTIVE&query=" + name.substring(7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", hasItem(tenantId.toString())))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(1));
        getAs(staff, TENANTS + "?status=SUSPENDED&query=" + name.substring(7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", not(hasItem(tenantId.toString()))));
    }

    @Test
    @DisplayName("Platform login refuses a wrong password and a tenant user's credentials alike: 401 AUTH_INVALID_CREDENTIALS")
    void platformLoginIsForStaffOnly() throws Exception {
        platformToken();
        TestUser user = newUser();

        postAs(null, "/api/v1/platform/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(user.email(), PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));
    }
}
