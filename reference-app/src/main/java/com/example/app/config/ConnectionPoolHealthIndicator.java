package com.example.app.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Readiness judged by the connection pool's queue, read from memory without borrowing a
 * connection (the {@code connectionPool} contributor of {@code /actuator/health/readiness}).
 *
 * <p>Not Boot's {@code db} indicator: it borrows a connection and waits in the same queue as the
 * requests, so under a saturated pool it answers UP late, or DOWN only after the connection
 * timeout. This answers OUT_OF_SERVICE as soon as a full pool's worth of requests is waiting, and
 * the load balancer sends new traffic elsewhere until the queue drains.</p>
 */
@Component("connectionPool")
class ConnectionPoolHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    ConnectionPoolHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

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
