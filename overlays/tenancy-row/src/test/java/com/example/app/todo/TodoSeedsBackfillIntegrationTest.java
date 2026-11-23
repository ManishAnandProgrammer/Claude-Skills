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
// media:begin
import java.util.Map;
// media:end
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    /** Read as the owner told that tenant: the policy is forced on the owner too. */
    private int rowsOf(UUID tenantId, String tableAndFilter) throws SQLException {
        try (Connection owner = ownerConnection()) {
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            asOwner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenantId.toString());
            return asOwner.queryForObject("select count(*) from " + tableAndFilter, Integer.class);
        }
    }

    // media:begin
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
    // media:end
    // metadata:begin

    @Test
    @DisplayName("A tenant that existed before the numbers gets its counter from the backfill, after its highest number")
    void existingTenantsGetTheirCounter() throws Exception {
        UUID tenantId = testTenant().id();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            try {
                // The tenant as it was before the numbers shipped: no counter for it.
                asOwner.execute("alter table counters no force row level security");
                asOwner.update("delete from counters where tenant_id = ? and key = 'TODO_NUMBER'", tenantId);
                asOwner.execute("alter table counters force row level security");

                asOwner.execute(changesetStatements("0026-todo-numbers.sql", "0026-todo-number-counters-existing-tenants"));

                asOwner.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                Long highest = asOwner.queryForObject("select coalesce(max(number), 0) from todos", Long.class);
                assertThat(asOwner.queryForObject("select last_value from counters where key = 'TODO_NUMBER'", Long.class))
                        .isEqualTo(highest);
            } finally {
                owner.rollback();
            }
        }
    }

    @Test
    @DisplayName("A tenant without its counter (activated by an instance without the seed) numbers its first todo #1, then #2")
    void aTenantWithoutItsCounterNumbersFromOne() throws Exception {
        TestTenant tenant = newTenant();
        TestUser member = newUserIn(tenant);
        try (Connection owner = ownerConnection()) {
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            asOwner.execute("""
                    alter table counters no force row level security;
                    delete from counters where tenant_id = '%s' and key = 'TODO_NUMBER';
                    alter table counters force row level security""".formatted(tenant.id()));
        }
        assertThat(rowsOf(tenant.id(), "counters where key = 'TODO_NUMBER'")).isZero();
        String listId = read(postAs(member.accessToken(), "/api/v1/todo-lists", """
                {"name": "Numbered"}
                """).andExpect(status().isCreated()), "$.response.id");

        for (int expected = 1; expected <= 2; expected++) {
            postAs(member.accessToken(), "/api/v1/todos", """
                    {"todoListId": "%s", "title": "Numbered", "priority": "LOW"}
                    """.formatted(listId))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.response.number").value(expected));
        }
    }
    // metadata:end

    @Test
    @DisplayName("A tenant still PENDING when the backfills ran is activated on approval, with one row of each, and signs in")
    void aPendingTenantThroughTheBackfillsIsActivated() throws Exception {
        String ownerEmail = uniqueEmail("pending-owner");
        signupWithoutATenant(ownerEmail, PASSWORD).andExpect(status().isCreated());
        UUID pending = tenants.register("Pending through the backfills " + UUID.randomUUID(), ownerEmail);
        List<String> backfills = new ArrayList<>();
        // media:begin
        backfills.add(changesetStatements("0010-todo-attachments.sql", "0010-todo-attachment-policy-existing-tenants"));
        // media:end
        // metadata:begin
        backfills.add(changesetStatements("0014-todo-list-defaults.sql", "0014-todo-list-defaults-existing-tenants"));
        backfills.add(changesetStatements("0026-todo-numbers.sql", "0026-todo-number-counters-existing-tenants"));
        // metadata:end
        try (Connection owner = ownerConnection()) {
            new JdbcTemplate(new SingleConnectionDataSource(owner, true)).execute(String.join("\n", backfills));
        }
        // media:begin
        assertThat(rowsOf(pending, "upload_policies where purpose = 'TODO_ATTACHMENT'")).isOne();
        // media:end
        // metadata:begin
        assertThat(rowsOf(pending, "counters where key = 'TODO_NUMBER'")).isOne();
        // metadata:end

        postAs(platformToken(), "/api/v1/platform/tenants/" + pending + "/approve", "")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.response.status").value("ACTIVE"));

        // media:begin
        assertThat(rowsOf(pending, "upload_policies where purpose = 'TODO_ATTACHMENT'")).isOne();
        // media:end
        // metadata:begin
        assertThat(rowsOf(pending, "configuration_metadata where key = 'TODO_LIST_DEFAULTS'")).isOne();
        assertThat(rowsOf(pending, "counters where key = 'TODO_NUMBER'")).isOne();
        // metadata:end
        // metadata:begin
        String ownerToken = read(login(ownerEmail, PASSWORD).andExpect(status().isOk()), "$.response.accessToken");
        String listId = read(postAs(ownerToken, "/api/v1/todo-lists", """
                {"name": "First list"}
                """).andExpect(status().isCreated()), "$.response.id");
        postAs(ownerToken, "/api/v1/todos", """
                {"todoListId": "%s", "title": "First", "priority": "LOW"}
                """.formatted(listId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.number").value(1));
        // metadata:else
        // login(ownerEmail, PASSWORD).andExpect(status().isOk());
        // metadata:end
    }
}
