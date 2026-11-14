package com.example.app.todo;

import com.example.app.support.IntegrationTest;
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

/**
 * A purpose added to a running application reaches the tenants that already exist through its backfill
 * changeset (row mode; new tenants get it from TodoAttachmentPolicySeed). The changeset is read from
 * the changelog and run as the owner against a tenant that lacks the row, all in one transaction that is
 * rolled back: what would have happened on the deployment that shipped the purpose.
 */
class TodoAttachmentPolicyBackfillIntegrationTest extends IntegrationTest {

    private static final String CHANGELOG = "classpath:db/changelog/changes/0010-todo-attachments.sql";
    private static final String BACKFILL = ":0010-todo-attachment-policy-existing-tenants";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ResourceLoader resourceLoader;

    @Value("${app.liquibase.username}")
    private String ownerUser;

    @Value("${app.liquibase.password}")
    private String ownerPassword;

    /** The backfill changeset's statements: its lines up to the next changeset, comments left out. */
    private String backfillStatements() throws IOException {
        String changelog = resourceLoader.getResource(CHANGELOG).getContentAsString(StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        boolean inBackfill = false;
        for (String line : changelog.lines().toList()) {
            if (line.startsWith("--changeset ")) {
                inBackfill = line.contains(BACKFILL);
            } else if (inBackfill && !line.startsWith("--")) {
                statements.add(line);
            }
        }
        assertThat(statements).as("the backfill changeset in " + CHANGELOG).isNotEmpty();
        return String.join("\n", statements);
    }

    @Test
    @DisplayName("A tenant that existed before the purpose gets its starting limits from the backfill, the seed's numbers")
    void existingTenantsGetThePolicy() throws IOException, SQLException {
        UUID tenantId = testTenant().id();
        String jdbcUrl = dataSource.unwrap(HikariDataSource.class).getJdbcUrl();
        try (Connection owner = DriverManager.getConnection(jdbcUrl, ownerUser, ownerPassword)) {
            owner.setAutoCommit(false);
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            try {
                // The tenant as it was before the purpose shipped: no row for it.
                asOwner.execute("alter table upload_policies no force row level security");
                asOwner.update("delete from upload_policies where tenant_id = ? and purpose = 'TODO_ATTACHMENT'", tenantId);
                asOwner.execute("alter table upload_policies force row level security");

                asOwner.execute(backfillStatements());

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
}
