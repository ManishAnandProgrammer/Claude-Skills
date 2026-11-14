package com.example.app.tenant.schema;

import com.example.app.common.tenancy.TenantSchemas;
import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.example.app.tenant.service.TenantMigrations;
import com.example.app.tenant.service.TenantProvisioningJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Provisioning and the deploy-time migrations of tenant schemas, against the owner role's real
 * Liquibase runs. The test changelog ends with the {@code tenancy-probe} changeset; a schema is made to
 * miss it by deleting its record (and its table, or not, to make the rerun pass or fail).
 */
@WithoutTestTenant
class TenantMigrationsIntegrationTest extends IntegrationTest {

    private static final String PROBE_CHANGESET = "tenancy-probe";

    @Autowired
    private TenantMigrations migrations;

    @Autowired
    private TenantProvisioningJob provisioning;

    @Autowired
    @LiquibaseDataSource
    private DataSource ownerDataSource;

    private JdbcTemplate owner() {
        return new JdbcTemplate(ownerDataSource);
    }

    /** The schema misses the last changeset's record; with {@code dropTable}, its table too, so a rerun succeeds. */
    private void forgetProbeChangeset(TestTenant tenant, boolean dropTable) {
        String schema = TenantSchemas.of(tenant.id());
        owner().update("delete from %s.databasechangelog where id = ?".formatted(schema), PROBE_CHANGESET);
        if (dropTable) {
            owner().execute("drop table %s.tenancy_probe".formatted(schema));
        }
    }

    private Map<String, Object> record(UUID tenantId) {
        return jdbc.queryForMap("select status, last_changeset, error from tenant_migrations where tenant_id = ?", tenantId);
    }

    @Test
    @DisplayName("Provisioning creates the schema with every changeset, records it, and only then activates the tenant")
    void provisioningCreatesMigratesAndActivates() throws Exception {
        String staff = platformToken();
        String ownerEmail = uniqueEmail("owner");
        rawSignup(ownerEmail, PASSWORD).andExpect(status().isCreated());
        UUID tenantId = UUID.fromString(read(postAs(staff, "/api/v1/platform/tenants", """
                {"name": "Provisioned Ltd", "ownerEmail": "%s"}
                """.formatted(ownerEmail)).andExpect(status().isCreated()), "$.response.id"));
        assertThat(jdbc.queryForObject("select status from tenant_migrations where tenant_id = ?", String.class, tenantId))
                .isEqualTo("PROVISIONING");
        login(ownerEmail, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.tenantStatus").value("PENDING"));

        provisioning.provisionPending();

        Map<String, Object> record = record(tenantId);
        assertThat(record.get("status")).isEqualTo("MIGRATED");
        assertThat(record.get("last_changeset")).isEqualTo(PROBE_CHANGESET);
        assertThat(jdbc.queryForObject("select count(*) from pg_namespace where nspname = ?", Integer.class, TenantSchemas.of(tenantId)))
                .isOne();
        assertThat(jdbc.queryForObject("""
                select source from tenant_status_changes where tenant_id = ? and to_status = 'ACTIVE'
                """, String.class, tenantId)).isEqualTo("PROVISIONING");
        login(ownerEmail, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A tenant rejected after its approval, before provisioning ran, never gets a schema")
    void rejectedBeforeProvisioningGetsNoSchema() throws Exception {
        String staff = platformToken();
        String ownerEmail = uniqueEmail("owner");
        rawSignup(ownerEmail, PASSWORD).andExpect(status().isCreated());
        UUID tenantId = UUID.fromString(read(postAs(staff, "/api/v1/platform/tenants", """
                {"name": "Rejected Ltd", "ownerEmail": "%s"}
                """.formatted(ownerEmail)).andExpect(status().isCreated()), "$.response.id"));
        patchAs(staff, "/api/v1/platform/tenants/" + tenantId + "/status", """
                {"status": "REJECTED", "version": 0}
                """).andExpect(status().isOk());

        provisioning.provisionPending();

        assertThat(record(tenantId).get("status")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from pg_namespace where nspname = ?", Integer.class, TenantSchemas.of(tenantId)))
                .isZero();
        assertThat(jdbc.queryForObject("select status from tenants where id = ?", String.class, tenantId)).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("A failing schema is recorded and skipped while the others migrate; after the fix, a rerun applies only what is missing")
    void failingSchemaIsSkippedThenResumed() throws Exception {
        TestTenant broken = newTenant();
        TestTenant behind = newTenant();
        forgetProbeChangeset(broken, false);
        forgetProbeChangeset(behind, true);

        TenantMigrations.Run firstRun = migrations.run();

        assertThat(firstRun.ran()).isTrue();
        assertThat(firstRun.failed()).contains(broken.id()).doesNotContain(behind.id());
        assertThat(record(broken.id()).get("status")).isEqualTo("FAILED");
        assertThat((String) record(broken.id()).get("error")).contains("tenancy_probe");
        assertThat(record(behind.id()).get("status")).isEqualTo("MIGRATED");
        assertThat(owner().queryForObject("select count(*) from %s.tenancy_probe".formatted(TenantSchemas.of(behind.id())), Integer.class))
                .as("the missing changeset ran again in the schema behind").isZero();

        owner().execute("drop table %s.tenancy_probe".formatted(TenantSchemas.of(broken.id())));
        TenantMigrations.Run secondRun = migrations.run();

        assertThat(secondRun.failed()).doesNotContain(broken.id());
        assertThat(record(broken.id())).containsEntry("status", "MIGRATED").containsEntry("last_changeset", PROBE_CHANGESET)
                .containsEntry("error", null);
        Integer executions = owner().queryForObject("select count(*) from %s.databasechangelog where id = ?"
                .formatted(TenantSchemas.of(broken.id())), Integer.class, PROBE_CHANGESET);
        assertThat(executions).isOne();
    }

    @Test
    @DisplayName("While another migrator holds the lock, a run and a provisioning run are skipped")
    void runsAreSkippedWhileTheLockIsHeld() {
        TenantMigrations.Run run = owner().execute((ConnectionCallback<TenantMigrations.Run>) connection -> {
            try (PreparedStatement lock = connection.prepareStatement("select pg_advisory_lock(hashtext('tenant-schema-migrations'))")) {
                lock.execute();
            }
            try {
                provisioning.provisionPending();
                return migrations.run();
            } finally {
                try (PreparedStatement unlock = connection.prepareStatement("select pg_advisory_unlock(hashtext('tenant-schema-migrations'))")) {
                    unlock.execute();
                }
            }
        });

        assertThat(run.ran()).isFalse();
        assertThat(run.migrated()).isZero();
    }

    @Test
    @DisplayName("The template schema Hibernate validates against carries every tenant changeset")
    void templateIsCurrent() throws Exception {
        TestTenant tenant = newTenant();
        String countChangesets = "select count(*) from %s.databasechangelog";

        Integer template = owner().queryForObject(countChangesets.formatted(TenantSchemas.TEMPLATE), Integer.class);
        Integer provisioned = owner().queryForObject(countChangesets.formatted(TenantSchemas.of(tenant.id())), Integer.class);
        assertThat(template).isEqualTo(provisioned).isPositive();
    }
}
