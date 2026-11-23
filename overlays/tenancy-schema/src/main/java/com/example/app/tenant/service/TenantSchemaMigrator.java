package com.example.app.tenant.service;

import com.example.app.common.tenancy.TenantSchemas;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Schema names are derived from tenant ids ({@link TenantSchemas}), never from input, so building the DDL from them
 * is safe.
 *
 * <p>Two migrators on a brand-new schema fail while creating Liquibase's own tables, and on an
 * existing one the second waits on Liquibase's lock, polling every 10 s. So every run over tenant
 * schemas holds one PostgreSQL advisory lock ({@link #runLocked}): one migrator in the whole cluster.</p>
 */
@Component
class TenantSchemaMigrator {

    private static final String MIGRATIONS_LOCK = "tenant-schema-migrations";
    /** The template's own: instances starting together wait on it, and never on a long tenant run. */
    private static final String TEMPLATE_LOCK = "tenant-template-migration";
    private static final String LAST_CHANGESET = """
            select id from %s.databasechangelog order by orderexecuted desc limit 1""";

    private final DataSource owner;
    private final JdbcTemplate ownerJdbc;
    private final ResourceLoader resourceLoader;
    private final TenantSchemaProperties properties;

    // Hand-written: the owner's JdbcTemplate is this class's own; as a bean, any class injecting a
    // JdbcTemplate could get the owner role's instead of the application's.
    TenantSchemaMigrator(@LiquibaseDataSource DataSource owner, ResourceLoader resourceLoader, TenantSchemaProperties properties) {
        this.owner = owner;
        this.ownerJdbc = new JdbcTemplate(owner);
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    boolean runLocked(Runnable work) {
        Boolean ran = ownerJdbc.execute((ConnectionCallback<Boolean>) connection -> {
            if (!advisoryLock(connection, "select pg_try_advisory_lock(hashtextextended(?, 0))", MIGRATIONS_LOCK)) {
                return false;
            }
            try {
                work.run();
            } finally {
                advisoryLock(connection, "select pg_advisory_unlock(hashtextextended(?, 0))", MIGRATIONS_LOCK);
            }
            return true;
        });
        return Boolean.TRUE.equals(ran);
    }

    /** Idempotent: provisioning resumes after a failure at any step. */
    void createSchema(UUID tenantId) {
        create(TenantSchemas.of(tenantId));
    }

    String migrate(UUID tenantId) {
        return update(TenantSchemas.of(tenantId));
    }

    void migrateTemplate() {
        ownerJdbc.execute((ConnectionCallback<Boolean>) connection -> {
            waitForLock(connection, TEMPLATE_LOCK);
            try {
                create(TenantSchemas.TEMPLATE);
                update(TenantSchemas.TEMPLATE);
            } finally {
                advisoryLock(connection, "select pg_advisory_unlock(hashtextextended(?, 0))", TEMPLATE_LOCK);
            }
            return true;
        });
    }

    private void create(String schema) {
        ownerJdbc.execute("create schema if not exists " + schema);
        ownerJdbc.execute("grant usage on schema %s to %s".formatted(schema, quoted(properties.appRole())));
    }

    private String update(String schema) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(owner);
        liquibase.setChangeLog(properties.tenantChangelog());
        liquibase.setDefaultSchema(schema);
        liquibase.setLiquibaseSchema(schema);
        liquibase.setResourceLoader(resourceLoader);
        try {
            liquibase.afterPropertiesSet();
        } catch (LiquibaseException failure) {
            throw new IllegalStateException("Migrating schema %s failed".formatted(schema), failure);
        }
        // The owner's default privileges grant the application role every new table, Liquibase's own included.
        ownerJdbc.execute("revoke all on %1$s.databasechangelog, %1$s.databasechangeloglock from %2$s"
                .formatted(schema, quoted(properties.appRole())));
        return ownerJdbc.queryForList(LAST_CHANGESET.formatted(schema), String.class).stream().findFirst().orElse("");
    }

    /** The root cause names the changeset's problem. */
    static String describe(RuntimeException failure) {
        return "%s: %s".formatted(failure.getMessage(), NestedExceptionUtils.getMostSpecificCause(failure).getMessage());
    }

    private String quoted(String role) {
        return ownerJdbc.queryForObject("select quote_ident(?)", String.class, role);
    }

    /**
     * The owner's connections carry Liquibase's {@code lock_timeout}, which an advisory lock's wait honours too:
     * without lifting it, an instance queued behind other instances' template runs for longer than that fails its
     * startup. Only this connection: the owner's data source is unpooled, so the setting ends with it, and Liquibase
     * runs on connections of its own.
     */
    private static void waitForLock(Connection connection, String lock) throws SQLException {
        try (Statement noTimeout = connection.createStatement()) {
            noTimeout.execute("set lock_timeout = 0");
        }
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_lock(hashtextextended(?, 0))")) {
            statement.setString(1, lock);
            statement.execute();
        }
    }

    private static boolean advisoryLock(Connection connection, String sql, String lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lock);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
