package com.example.app.tenant.schema;

import com.example.app.common.tenancy.TenantScope;
import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema-per-tenant hook, as the application role sees it (experiment E4 of the design): each
 * transaction reaches its own tenant's schema only, and nothing of it survives the transaction.
 */
@WithoutTestTenant
class SchemaIsolationIntegrationTest extends IntegrationTest {

    private static final String COUNT_PROBES = "select count(*) from tenancy_probe";
    /** Two tenants with 2 and 3 probe rows, shared by the tests of this class (provisioning is slow). */
    private static TestTenant first;
    private static TestTenant second;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    /** The application's own JdbcTemplate, unlike the base's fixture one: it sees only what the hook set. */
    @Autowired
    private JdbcTemplate appJdbc;

    @BeforeEach
    void twoTenantsWithRows() throws Exception {
        if (Objects.isNull(first)) {
            first = newTenant();
            second = newTenant();
            insertProbes(first, 2);
            insertProbes(second, 3);
        }
    }

    /** Written through the application's own path: a transaction in the tenant's scope. */
    private void insertProbes(TestTenant tenant, int count) {
        TenantScope.runAs(tenant.id(), () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int index = 0; index < count; index++) {
                appJdbc.update("insert into tenancy_probe (id, label) values (?, ?)", UUID.randomUUID(), tenant.id().toString());
            }
        }));
    }

    private long countIn(TestTenant tenant, boolean readOnly) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(readOnly);
        return TenantScope.callAs(tenant.id(), () -> transaction.execute(status -> appJdbc.queryForObject(COUNT_PROBES, Long.class)));
    }

    /** Every connection the pool holds, borrowed at once, as a plain JDBC user (no Hibernate, no hook) would. */
    private List<String> searchPathOfEveryPooledConnection() throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<Connection> borrowed = new ArrayList<>();
        List<String> searchPaths = new ArrayList<>();
        try {
            for (int index = 0; index < pool.getMaximumPoolSize(); index++) {
                Connection connection = pool.getConnection();
                borrowed.add(connection);
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("show search_path")) {
                    result.next();
                    searchPaths.add(result.getString(1));
                }
            }
        } finally {
            for (Connection connection : borrowed) {
                connection.rollback();
                connection.close();
            }
        }
        return searchPaths;
    }

    @Test
    @DisplayName("A tenant's transaction sees its own schema's rows only, read-only or not")
    void eachTenantSeesItsOwnRows() {
        assertThat(countIn(first, true)).isEqualTo(2);
        assertThat(countIn(second, true)).isEqualTo(3);
        assertThat(countIn(first, false)).isEqualTo(2);
    }

    @Test
    @DisplayName("After a commit and after a rollback, every pooled connection is back on platform: no tenant's tables")
    void nothingLeaksToTheNextBorrower() throws Exception {
        countIn(first, false);
        assertThat(searchPathOfEveryPooledConnection()).containsOnly("platform");

        assertThatThrownBy(() -> TenantScope.runAs(second.id(), () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    appJdbc.queryForObject(COUNT_PROBES, Long.class);
                    throw new IllegalStateException("roll back");
                }))).hasMessage("roll back");
        assertThat(searchPathOfEveryPooledConnection()).containsOnly("platform");
        assertThatThrownBy(() -> appJdbc.queryForObject(COUNT_PROBES, Long.class))
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause().hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("REQUIRES_NEW under another tenant gets that tenant's schema; the outer transaction keeps its own")
    void requiresNewUnderAnotherTenant() {
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<Long> counts = TenantScope.callAs(first.id(), () -> new TransactionTemplate(transactionManager).execute(status -> {
            long before = appJdbc.queryForObject(COUNT_PROBES, Long.class);
            long other = TenantScope.callAs(second.id(), () -> inner.execute(innerStatus -> appJdbc.queryForObject(COUNT_PROBES, Long.class)));
            long after = appJdbc.queryForObject(COUNT_PROBES, Long.class);
            return List.of(before, other, after);
        }));

        assertThat(counts).containsExactly(2L, 3L, 2L);
    }

    @Test
    @DisplayName("A tenant switched inside an open transaction changes nothing: the tenant is fixed when it begins")
    void switchInsideATransactionIsIgnored() {
        long count = TenantScope.callAs(first.id(), () -> new TransactionTemplate(transactionManager).execute(status ->
                TenantScope.callAs(second.id(), () -> appJdbc.queryForObject(COUNT_PROBES, Long.class))));

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Without a tenant, platform tables work and a tenant's table doesn't exist")
    void noTenantReachesThePlatformOnly() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Long tenants = transaction.execute(status -> appJdbc.queryForObject("select count(*) from tenants", Long.class));
        assertThat(tenants).isPositive();
        assertThatThrownBy(() -> transaction.execute(status -> appJdbc.queryForObject(COUNT_PROBES, Long.class)))
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause().hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("A read-only transaction is read-only in the database and refuses a write; one that runs no SQL borrows no connection")
    void readOnlyAndLazyBorrowing() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        Integer activeWithoutSql = TenantScope.callAs(first.id(), () -> readOnly.execute(status ->
                pool.getHikariPoolMXBean().getActiveConnections()));
        assertThat(activeWithoutSql).isZero();
        String flag = TenantScope.callAs(first.id(), () -> readOnly.execute(status ->
                appJdbc.queryForObject("show transaction_read_only", String.class)));
        assertThat(flag).isEqualTo("on");
        assertThatThrownBy(() -> TenantScope.runAs(first.id(), () -> readOnly.executeWithoutResult(status ->
                appJdbc.update("insert into tenancy_probe (id, label) values (?, 'read-only')", UUID.randomUUID()))))
                .hasMessageContaining("read-only transaction");
        assertThat(countIn(first, true)).isEqualTo(2);
    }
}
