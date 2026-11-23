package com.example.app.support;

import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Plain JDBC never passes the application's connection hook (that runs for Hibernate's sessions), so each connection
 * handed out here first gets the test tenant on its transaction's {@code app.tenant_id}. Transaction-local: the pool
 * gets its connections back clean.
 */
class TenantFixtureDataSource extends TransactionAwareDataSourceProxy {

    private static final String TENANT = "select set_config('app.tenant_id', ?, true)";

    private final Supplier<UUID> tenant;

    TenantFixtureDataSource(DataSource dataSource, Supplier<UUID> tenant) {
        super(dataSource);
        this.tenant = tenant;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        try (PreparedStatement statement = connection.prepareStatement(TENANT)) {
            statement.setString(1, tenant.get().toString());
            statement.execute();
        }
        return connection;
    }
}
