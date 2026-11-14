package com.example.app.common.tenancy;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Hibernate's connection hook in the row mode: the only code that writes {@code app.tenant_id}, the
 * setting every row-level security policy reads. Hibernate calls it when a transaction's first
 * statement needs a connection (release-after-transaction mode), so a transaction that runs no SQL
 * borrows nothing.
 *
 * <p>It runs one parameterised statement as the transaction's first: the tenant and, for a read-only
 * transaction, the read-only flag (in this mode Spring no longer sets it). Both are transaction-local,
 * so the commit or rollback clears them and the connection returns to the pool without a tenant: no
 * reset step that could be forgotten, and a plain JDBC borrower sees no tenant's rows.</p>
 *
 * <p>{@link TenantContext#NO_TENANT} sets an empty tenant: the policies match no row, so tenant tables
 * read as empty and refuse writes, while the platform's tables (no policy) work.</p>
 */
public class TransactionLocalTenantProvider implements MultiTenantConnectionProvider<UUID> {

    private static final String TENANT_TRANSACTION = """
            select set_config('app.tenant_id', ?, true), set_config('transaction_read_only', ?, true)""";
    private static final String NO_TENANT_SETTING = "";

    private final DataSource dataSource;

    public TransactionLocalTenantProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection(UUID tenantId) throws SQLException {
        Connection connection = dataSource.getConnection();
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        String tenantSetting = TenantContext.NO_TENANT.equals(tenantId) ? NO_TENANT_SETTING : tenantId.toString();
        try (PreparedStatement statement = connection.prepareStatement(TENANT_TRANSACTION)) {
            statement.setString(1, tenantSetting);
            statement.setString(2, readOnly ? "on" : "off");
            statement.execute();
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

    /** Hibernate's startup work (schema validation) reads the catalog only: no tenant needed. */
    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
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
}
