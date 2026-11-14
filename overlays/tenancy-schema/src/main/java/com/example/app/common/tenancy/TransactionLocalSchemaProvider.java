package com.example.app.common.tenancy;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Hibernate's connection hook in the schema-per-tenant mode: the only code that writes
 * {@code search_path}. Hibernate calls it when a transaction's first statement needs a connection
 * (release-after-transaction mode), so a transaction that runs no SQL borrows nothing.
 *
 * <p>It runs one parameterised statement as the transaction's first: the tenant's schema and, for a
 * read-only transaction, the read-only flag (in this mode Spring no longer sets it). Both are
 * transaction-local, so the commit or rollback clears them and the connection returns to the pool on
 * the role's default {@code search_path} ({@code platform}): no reset step that could be forgotten, and
 * a plain JDBC borrower never sees the previous tenant's tables.</p>
 *
 * <p>{@link TenantContext#NO_TENANT} keeps the default: the platform's tables resolve, a tenant's table
 * doesn't exist.</p>
 */
public class TransactionLocalSchemaProvider implements MultiTenantConnectionProvider<UUID> {

    private static final String TENANT_TRANSACTION = """
            select set_config('search_path', quote_ident(?), true), set_config('transaction_read_only', ?, true)""";
    private static final String READ_ONLY_TRANSACTION = "select set_config('transaction_read_only', 'on', true)";
    // Session-level for Hibernate's startup work (schema validation), which may span its own commits;
    // reset before the connection goes back.
    private static final String TEMPLATE_SESSION = "set search_path to " + TenantSchemas.TEMPLATE;
    private static final String RESET_SESSION = "reset search_path";

    private final DataSource dataSource;

    public TransactionLocalSchemaProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection(UUID tenantId) throws SQLException {
        Connection connection = dataSource.getConnection();
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        try {
            if (!TenantContext.NO_TENANT.equals(tenantId)) {
                setTenant(connection, tenantId, readOnly);
            } else if (readOnly) {
                execute(connection, READ_ONLY_TRANSACTION);
            }
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    @Override
    public void releaseConnection(UUID tenantId, Connection connection) throws SQLException {
        connection.close();
    }

    /** Hibernate's startup work: it validates the entities against the template schema. */
    @Override
    public Connection getAnyConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            execute(connection, TEMPLATE_SESSION);
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        try {
            execute(connection, RESET_SESSION);
            connection.commit();
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        throw new UnsupportedOperationException("Not unwrappable: " + unwrapType.getName());
    }

    private static void setTenant(Connection connection, UUID tenantId, boolean readOnly) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TENANT_TRANSACTION)) {
            statement.setString(1, TenantSchemas.of(tenantId));
            statement.setString(2, readOnly ? "on" : "off");
            statement.execute();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
