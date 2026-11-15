package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.example.app.tenant.api.Tenants;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The todo module's per-tenant starting rows (row mode): new tenants get them from their
 * {@code TenantProvisioned} seeds (TodoAttachmentPolicySeed, TodoListDefaultsSeed), the tenants that
 * exist when a row ships from a backfill changeset. Each backfill is read from its changelog and run as
 * the owner: what the deployment that shipped it did. A backfill also reaches a tenant still PENDING,
 * so that tenant's approval must keep the rows its seeds find. Without the test tenant's scope: each
 * request works in the tenant its token names.
 */
@WithoutTestTenant
class TodoSeedsBackfillIntegrationTest extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired
    private Tenants tenants;

    @Value("${app.liquibase.username}")
    private String ownerUser;

    @Value("${app.liquibase.password}")
    private String ownerPassword;

    /** A changeset's statements: its lines up to the next changeset, comments left out. */
    private String changesetStatements(String changelog, String changesetId) throws IOException {
        String text = resourceLoader.getResource("classpath:db/changelog/changes/" + changelog).getContentAsString(StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        boolean inChangeset = false;
        for (String line : text.lines().toList()) {
            if (line.startsWith("--changeset ")) {
                inChangeset = line.endsWith(":" + changesetId);
            } else if (inChangeset && !line.startsWith("--")) {
                statements.add(line);
            }
        }
        assertThat(statements).as("the changeset " + changesetId + " in " + changelog).isNotEmpty();
        return String.join("\n", statements);
    }

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(dataSource.unwrap(HikariDataSource.class).getJdbcUrl(), ownerUser, ownerPassword);
    }

    /** The rows of {@code tableAndFilter} in {@code tenantId}, read as the owner told that tenant (the policy is forced on it too). */
    private int rowsOf(UUID tenantId, String tableAndFilter) throws SQLException {
        try (Connection owner = ownerConnection()) {
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            asOwner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenantId.toString());
            return asOwner.queryForObject("select count(*) from " + tableAndFilter, Integer.class);
        }
    }

    @Test
    @DisplayName("A tenant that existed before the purpose gets its starting limits from the backfill, the seed's numbers")
    void existingTenantsGetThePolicy() throws IOException, SQLException {
        UUID tenantId = testTenant().id();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            try {
                // The tenant as it was before the purpose shipped: no row for it.
                asOwner.execute("alter table upload_policies no force row level security");
                asOwner.update("delete from upload_policies where tenant_id = ? and purpose = 'TODO_ATTACHMENT'", tenantId);
                asOwner.execute("alter table upload_policies force row level security");

                asOwner.execute(changesetStatements("0010-todo-attachments.sql", "0010-todo-attachment-policy-existing-tenants"));

                asOwner.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                Map<String, Object> policy = asOwner.queryForMap("""
                        select min_size_bytes, max_size_bytes, min_files, max_files, content_types::text as types
                          from upload_policies where purpose = 'TODO_ATTACHMENT'""");
                assertThat(policy).containsEntry("min_size_bytes", 1L).containsEntry("max_size_bytes", 10_485_760L)
                        .containsEntry("min_files", 0).containsEntry("max_files", 5)
                        .containsEntry("types", "{application/pdf,image/jpeg,image/png}");
            } finally {
                owner.rollback();
            }
        }
    }

    @Test
    @DisplayName("A tenant still PENDING when the backfills ran is activated on approval, with one row of each, and signs in")
    void aPendingTenantThroughTheBackfillsIsActivated() throws Exception {
        String ownerEmail = uniqueEmail("pending-owner");
        rawSignup(ownerEmail, PASSWORD).andExpect(status().isCreated());
        UUID pending = tenants.register("Pending through the backfills " + UUID.randomUUID(), ownerEmail);
        // The deployment that ships the rows, committed while the registration waits for staff.
        List<String> backfills = new ArrayList<>();
        backfills.add(changesetStatements("0010-todo-attachments.sql", "0010-todo-attachment-policy-existing-tenants"));
        // metadata:begin
        backfills.add(changesetStatements("0014-todo-list-defaults.sql", "0014-todo-list-defaults-existing-tenants"));
        // metadata:end
        try (Connection owner = ownerConnection()) {
            new JdbcTemplate(new SingleConnectionDataSource(owner, true)).execute(String.join("\n", backfills));
        }
        assertThat(rowsOf(pending, "upload_policies where purpose = 'TODO_ATTACHMENT'")).isOne();

        postAs(platformToken(), "/api/v1/platform/tenants/" + pending + "/approve", "")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.response.status").value("ACTIVE"));

        assertThat(rowsOf(pending, "upload_policies where purpose = 'TODO_ATTACHMENT'")).isOne();
        // metadata:begin
        assertThat(rowsOf(pending, "configuration_metadata where key = 'TODO_LIST_DEFAULTS'")).isOne();
        // metadata:end
        login(ownerEmail, PASSWORD).andExpect(status().isOk());
    }
}
