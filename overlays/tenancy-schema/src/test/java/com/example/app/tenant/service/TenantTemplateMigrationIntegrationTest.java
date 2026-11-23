package com.example.app.tenant.service;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@WithoutTestTenant
class TenantTemplateMigrationIntegrationTest extends IntegrationTest {

    private static final String TEMPLATE_LOCK = "select pg_advisory_lock(hashtextextended('tenant-template-migration', 0))";
    private static final String TEMPLATE_UNLOCK = "select pg_advisory_unlock(hashtextextended('tenant-template-migration', 0))";

    @Autowired
    private TenantSchemaMigrator migrator;

    @Autowired
    @LiquibaseDataSource
    private DataSource ownerDataSource;

    @Value("${app.liquibase.lock-timeout}")
    private Duration ownerLockTimeout;

    @Test
    @DisplayName("A template migration queued behind another for longer than the owner's lock_timeout waits, then runs")
    void waitsLongerThanTheLockTimeout() throws Exception {
        try (Connection holder = ownerDataSource.getConnection();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            execute(holder, TEMPLATE_LOCK);
            Future<?> queued;
            try {
                queued = executor.submit(migrator::migrateTemplate);
                Thread.sleep(ownerLockTimeout.plusSeconds(1).toMillis());
                assertThat(queued).as("still waiting for the instance ahead").isNotDone();
            } finally {
                execute(holder, TEMPLATE_UNLOCK);
            }
            awaitResult(queued);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }
}
