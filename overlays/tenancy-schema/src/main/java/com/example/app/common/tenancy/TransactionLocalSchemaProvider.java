package com.example.app.common.tenancy;

import lombok.RequiredArgsConstructor;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * One statement sets the tenant's schema and, for a read-only transaction, the read-only flag (in this connection
 * handling mode Spring no longer sets it). Both are transaction-local, so the commit or rollback clears them and the
 * connection returns to the pool on the role's default {@code search_path} ({@code platform}): no reset step that
 * could be forgotten.
 */
@RequiredArgsConstructor
public class TransactionLocalSchemaProvider implements MultiTenantConnectionProvider<UUID> {

    private static final String TENANT_TRANSACTION = """
            select set_config('search_path', quote_ident(?), true), set_config('transaction_read_only', ?, true)""";
    private static final String READ_ONLY_TRANSACTION = "select set_config('transaction_read_only', 'on', true)";
    // Session-level for Hibernate's startup work (schema validation), which may span its own commits;
    // reset before the connection goes back.
    private static final String TEMPLATE_SESSION = "set search_path to " + TenantSchemas.TEMPLATE;
    private static final String RESET_SESSION = "reset search_path";

    private final DataSource dataSource;

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
