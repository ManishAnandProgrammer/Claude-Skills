package com.example.app.tenant.schema;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema per tenant's guard on the tenant schemas themselves (house-review TEN-16), read from PostgreSQL's
 * catalogue: a tenant schema's foreign keys stay inside it, the register aside, so {@code pg_dump -n} of
 * one schema holds a closed graph that restores into a database of its own. A person is a row of the
 * schema's own members, never {@code platform.users}.
 */
@WithoutTestTenant
class SchemaCatalogIntegrationTest extends IntegrationTest {

    /** Foreign keys from a tenant schema (the template included) to a table outside it but the register. */
    private static final String FOREIGN_KEYS_LEAVING_A_TENANT_SCHEMA = """
            select child_schema.nspname || '.' || f.conname
              from pg_constraint f
              join pg_class child on child.oid = f.conrelid
              join pg_namespace child_schema on child_schema.oid = child.relnamespace
              join pg_class parent on parent.oid = f.confrelid
              join pg_namespace parent_schema on parent_schema.oid = parent.relnamespace
             where f.contype = 'f'
               and (child_schema.nspname = 'tenant_template' or child_schema.nspname ~ '^t_[0-9a-f]{32}$')
               and parent_schema.oid <> child_schema.oid
               and not (parent_schema.nspname = 'platform' and parent.relname = 'tenants')
             order by 1""";

    @Autowired
    private DataSource dataSource;

    @Value("${app.liquibase.username}")
    private String ownerUser;

    @Value("${app.liquibase.password}")
    private String ownerPassword;

    private static List<String> foreignKeysLeavingATenantSchema(JdbcTemplate catalog) {
        return catalog.queryForList(FOREIGN_KEYS_LEAVING_A_TENANT_SCHEMA, String.class);
    }

    @Test
    @DisplayName("No tenant schema references a platform table but tenants: people are the schema's own members")
    void tenantSchemasReferenceNoPlatformTable() throws Exception {
        newTenant();

        assertThat(foreignKeysLeavingATenantSchema(jdbc)).isEmpty();
    }

    @Test
    @DisplayName("The guard catches a tenant table with a key to platform.users")
    void theGuardCatchesAKeyToUsers() throws SQLException {
        String jdbcUrl = dataSource.unwrap(HikariDataSource.class).getJdbcUrl();
        try (Connection owner = DriverManager.getConnection(jdbcUrl, ownerUser, ownerPassword)) {
            owner.setAutoCommit(false);
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            try {
                asOwner.execute("""
                        create table tenant_template.guard_probe (
                            id uuid primary key,
                            owner_id uuid not null constraint fk_guard_probe_owner_id references platform.users (id))""");

                assertThat(foreignKeysLeavingATenantSchema(asOwner)).contains("tenant_template.fk_guard_probe_owner_id");
            } finally {
                owner.rollback();
            }
        }
    }
}
