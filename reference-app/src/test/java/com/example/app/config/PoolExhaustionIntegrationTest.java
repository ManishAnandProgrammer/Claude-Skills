package com.example.app.config;

import com.example.app.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PoolExhaustionIntegrationTest extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("An exhausted pool answers 503 DATABASE_UNAVAILABLE with a Retry-After")
    void exhaustedPoolAnswers503WithRetryAfter() throws Exception {
        TestUser user = newUser();
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<Connection> held = new ArrayList<>();
        try {
            // From the pool itself: the DataSource bean may hand out lazy handles that borrow nothing
            // until a statement runs.
            for (int taken = 0; taken < pool.getMaximumPoolSize(); taken++) {
                held.add(pool.getConnection());
            }

            getAs(user.accessToken(), "/api/v1/users/me")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errorCode").value("DATABASE_UNAVAILABLE"))
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
        getAs(user.accessToken(), "/api/v1/users/me").andExpect(status().isOk());
        assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
    }
}
