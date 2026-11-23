package com.example.app.support;

import com.example.app.common.tenancy.TenantSchemas;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Plain JDBC never passes the application's connection hook (that runs for Hibernate's sessions), so each connection
 * handed out here first gets the test tenant's schema on its transaction's {@code search_path}. Transaction-local: the
 * pool gets its connections back clean.
 */
class TenantFixtureDataSource extends TransactionAwareDataSourceProxy {

    private static final String SEARCH_PATH = "select set_config('search_path', ?, true)";

    private final Supplier<UUID> tenant;

    TenantFixtureDataSource(DataSource dataSource, Supplier<UUID> tenant) {
        super(dataSource);
        this.tenant = tenant;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        try (PreparedStatement statement = connection.prepareStatement(SEARCH_PATH)) {
            statement.setString(1, TenantSchemas.of(tenant.get()) + ", " + TenantSchemas.PLATFORM);
            statement.execute();
        }
        return connection;
    }
}
