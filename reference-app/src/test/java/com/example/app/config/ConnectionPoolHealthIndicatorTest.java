package com.example.app.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.health.contributor.Status;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectionPoolHealthIndicatorTest {

    @ParameterizedTest(name = "{0} waiting on a pool of 10 → {1}")
    @CsvSource({"0, UP", "9, UP", "10, OUT_OF_SERVICE", "25, OUT_OF_SERVICE"})
    @DisplayName("Readiness goes OUT_OF_SERVICE once a pool's worth of requests waits for a connection")
    void readinessFollowsThePoolQueue(int waiting, String expected) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        HikariDataSource hikari = mock(HikariDataSource.class);
        HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
        when(dataSource.unwrap(HikariDataSource.class)).thenReturn(hikari);
        when(hikari.getHikariPoolMXBean()).thenReturn(pool);
        when(hikari.getMaximumPoolSize()).thenReturn(10);
        when(pool.getThreadsAwaitingConnection()).thenReturn(waiting);

        Status status = new ConnectionPoolHealthIndicator(dataSource).health().getStatus();

        assertThat(status.getCode()).isEqualTo(expected);
    }
}
