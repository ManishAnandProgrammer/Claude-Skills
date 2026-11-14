package com.example.app.config;

import com.example.app.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server-side limits are set on the app's connections, and in the right order: the statement
 * timeout fires before the socket timeout, so PostgreSQL cancels the query and the connection
 * survives, instead of the driver dropping it mid-query. Liquibase's own connection gets only the
 * lock timeout.
 */
class DatabaseTimeoutsIntegrationTest extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SpringLiquibase liquibase;

    @Test
    @DisplayName("Statement, lock and idle-in-transaction timeouts are set on the app's connections")
    void serverTimeoutsAreSet() {
        assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("25s");
        assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("3s");
        assertThat(jdbc.queryForObject("show idle_in_transaction_session_timeout", String.class)).isEqualTo("30s");
    }

    @Test
    @DisplayName("The statement timeout is shorter than the socket timeout")
    void statementTimeoutFiresBeforeTheSocketTimeout() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        Duration socketTimeout = Duration.ofSeconds(Long.parseLong(hikari.getDataSourceProperties().getProperty("socketTimeout")));
        Duration statementTimeout = Duration.ofSeconds(Long.parseLong(
                jdbc.queryForObject("show statement_timeout", String.class).replace("s", "")));

        assertThat(statementTimeout).isLessThan(socketTimeout);
    }

    @Test
    @DisplayName("The connection Liquibase migrates with waits at most the configured lock_timeout, has no statement timeout and is named")
    void liquibaseConnectionHasALockTimeoutOnly() {
        JdbcTemplate migrations = new JdbcTemplate(liquibase.getDataSource());

        assertThat(migrations.queryForObject("show lock_timeout", String.class)).isEqualTo("5s");
        assertThat(migrations.queryForObject("show statement_timeout", String.class)).isEqualTo("0");
        assertThat(migrations.queryForObject("show application_name", String.class)).isEqualTo(applicationName() + "-liquibase");
    }
}
