package com.example.app.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Not Boot's {@code db} indicator: it borrows a connection and waits in the requests' queue, so under a saturated
 * pool it answers UP late, or DOWN only after the connection timeout. This reads the queue from memory.
 */
@Component("connectionPool")
@RequiredArgsConstructor
class ConnectionPoolHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        HikariDataSource hikari;
        try {
            hikari = dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException notHikari) {
            return Health.unknown().withDetail("reason", "the data source is not a HikariCP pool").build();
        }
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (Objects.isNull(pool)) {
            return Health.unknown().withDetail("reason", "the pool has not started").build();
        }
        int maxWaiting = hikari.getMaximumPoolSize();
        int waiting = pool.getThreadsAwaitingConnection();
        Health.Builder health = waiting >= maxWaiting ? Health.outOfService() : Health.up();
        return health
                .withDetail("waiting", waiting)
                .withDetail("maxWaiting", maxWaiting)
                .withDetail("active", pool.getActiveConnections())
                .withDetail("idle", pool.getIdleConnections())
                .build();
    }
}
