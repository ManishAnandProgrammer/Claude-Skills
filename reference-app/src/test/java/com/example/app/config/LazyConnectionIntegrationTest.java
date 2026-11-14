package com.example.app.config;

import com.example.app.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/**
 * A transaction holds a pooled connection only from its first statement (LazyConnectionConfig):
 * measured with the pool's own active count, since Hibernate already counts the proxy as connected.
 * The jobs don't run in tests, so nothing else holds a connection meanwhile.
 */
class LazyConnectionIntegrationTest extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private HikariPoolMXBean pool;
    private TransactionTemplate readOnly;

    @BeforeEach
    void setUp() throws SQLException {
        pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
    }

    private String readOnlyFlag() {
        return (String) entityManager.createNativeQuery("select current_setting('transaction_read_only')").getSingleResult();
    }

    @Test
    @DisplayName("A read-only transaction borrows at its first statement, and PostgreSQL runs it read-only")
    void readOnlyTransactionBorrowsAtFirstStatement() {
        AtomicInteger activeBeforeSql = new AtomicInteger(-1);
        AtomicInteger activeAfterSql = new AtomicInteger(-1);

        String flag = readOnly.execute(status -> {
            activeBeforeSql.set(pool.getActiveConnections());
            String readOnlyFlag = readOnlyFlag();
            activeAfterSql.set(pool.getActiveConnections());
            return readOnlyFlag;
        });

        assertThat(activeBeforeSql).hasValue(0);
        assertThat(activeAfterSql).hasValue(1);
        assertThat(flag).isEqualTo("on");
    }

    @Test
    @DisplayName("A read-only transaction that runs no SQL never borrows a connection")
    void readOnlyTransactionWithoutSqlBorrowsNothing() {
        AtomicInteger active = new AtomicInteger(-1);

        readOnly.executeWithoutResult(status -> active.set(pool.getActiveConnections()));

        assertThat(active).hasValue(0);
    }

    @Test
    @DisplayName("A write transaction is not read-only")
    void writeTransactionIsNotReadOnly() {
        String flag = new TransactionTemplate(transactionManager).execute(status -> readOnlyFlag());

        assertThat(flag).isEqualTo("off");
    }

    @Test
    @DisplayName("The pool's metrics still reach the scrape through the proxy")
    void poolMetricsAreScraped() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hikaricp_connections_active")))
                .andExpect(content().string(containsString("hikaricp_connections_acquire_seconds_count")));
    }
}
