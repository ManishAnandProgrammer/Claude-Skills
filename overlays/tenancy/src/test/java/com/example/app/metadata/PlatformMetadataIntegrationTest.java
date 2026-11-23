package com.example.app.metadata;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithoutTestTenant
@ResourceLock(MetadataIntegrationTest.FIXED_KEY)
class PlatformMetadataIntegrationTest extends IntegrationTest {

    private static final String VALUE = """
            {"reminderMinutesBefore": 45, "archiveDoneTodos": false, "dailyDigestAt": "07:15"}""";

    private StoredMetadata seeded;

    @BeforeEach
    void startFromTheDefaults() {
        seeded = storedMetadata(MetadataIntegrationTest.FIXED_KEY);
        storeMetadata(MetadataIntegrationTest.FIXED_KEY, "{}");
    }

    @AfterEach
    void restoreTheSeededKey() {
        restoreMetadata(seeded);
    }

    @Test
    @DisplayName("Staff read a tenant's key and replace it with that version, as its admin would; the tenant reads it, the change is audited")
    void staffEditATenantsConfiguration() throws Exception {
        TestUser admin = newAdmin();
        UUID tenantId = testTenant().id();
        String path = "/api/v1/platform/tenants/" + tenantId + "/metadata/" + MetadataIntegrationTest.FIXED_KEY;
        Number version = read(getAs(platformToken(), path).andExpect(status().isOk()), "$.response.version");

        putAs(platformToken(), path, """
                {"value": %s, "version": %d}
                """.formatted(VALUE, version.longValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.value.reminderMinutesBefore").value(45))
                .andExpect(jsonPath("$.response.version").value(1));

        getAs(admin.accessToken(), "/api/v1/metadata/" + MetadataIntegrationTest.FIXED_KEY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.value.reminderMinutesBefore").value(45));
        Integer audited = jdbc.queryForObject("""
                select count(*) from platform_audit where tenant_id = ? and action = 'METADATA_UPDATE' and detail = ?
                """, Integer.class, tenantId, MetadataIntegrationTest.FIXED_KEY);
        assertThat(audited).isOne();
    }

    @Test
    @DisplayName("A tenant token can't reach the platform's read or edit: 401; an unknown tenant is 403 TENANT_NOT_ACTIVE")
    void onlyStaffEditAndOnlyKnownTenants() throws Exception {
        TestUser admin = newAdmin();
        String path = "/api/v1/platform/tenants/%s/metadata/" + MetadataIntegrationTest.FIXED_KEY;
        String body = """
                {"value": %s, "version": 0}
                """.formatted(VALUE);

        putAs(admin.accessToken(), path.formatted(testTenant().id()), body)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(admin.accessToken(), path.formatted(testTenant().id()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(platformToken(), path.formatted(UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_ACTIVE"));
        putAs(platformToken(), path.formatted(UUID.randomUUID()), body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_ACTIVE"));
    }
}
