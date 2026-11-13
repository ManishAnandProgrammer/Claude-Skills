package com.example.app.common.exception;

import com.example.app.common.web.RetryAfterProperties;
import jakarta.persistence.PersistenceException;
import org.hibernate.QueryTimeoutException;
import org.hibernate.exception.LockTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TransientDatabaseFailureTest {

    @Test
    @DisplayName("An exhausted pool, however wrapped, is DATABASE_UNAVAILABLE")
    void poolTimeoutIsUnavailable() {
        RuntimeException wrapped = new RuntimeException(new SQLTransientConnectionException("TodoPool - Connection is not available"));

        assertThat(TransientDatabaseFailure.of(wrapped)).contains(TransientDatabaseFailure.UNAVAILABLE);
    }

    @Test
    @DisplayName("A statement timeout under a bare PersistenceException is QUERY_TIMEOUT")
    void statementTimeoutIsQueryTimeout() {
        PersistenceException wrapped = new PersistenceException(
                new QueryTimeoutException("canceling statement due to statement timeout", new SQLException("timeout", "57014"), "select 1"));

        assertThat(TransientDatabaseFailure.of(wrapped)).contains(TransientDatabaseFailure.QUERY_TIMEOUT);
    }

    @Test
    @DisplayName("A lost connection (SQLSTATE class 08) is DATABASE_UNAVAILABLE")
    void connectionExceptionIsUnavailable() {
        assertThat(TransientDatabaseFailure.of(new SQLException("connection lost", "08006")))
                .contains(TransientDatabaseFailure.UNAVAILABLE);
    }

    @Test
    @DisplayName("A constraint violation is not transient: it stays a 500")
    void constraintViolationIsNotTransient() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("duplicate",
                new SQLException("duplicate key", "23505"));

        assertThat(TransientDatabaseFailure.of(violation)).isEmpty();
    }

    @Test
    @DisplayName("A lock wait past lock_timeout (SQLSTATE 55P03) is DATABASE_CONTENTION")
    void lockTimeoutIsContention() {
        LockTimeoutException lockTimeout = new LockTimeoutException("lock timeout", new SQLException("lock", "55P03"), "update todos");

        assertThat(TransientDatabaseFailure.of(new RuntimeException(lockTimeout))).contains(TransientDatabaseFailure.CONTENTION);
    }

    @ParameterizedTest
    @EnumSource(TransientDatabaseFailure.class)
    @DisplayName("Each kind answers within its configured Retry-After range, and the value is really jittered")
    void retryAfterIsJitteredWithinTheConfiguredRange(TransientDatabaseFailure failure) {
        RetryAfterProperties ranges = new RetryAfterProperties(range(1, 3), range(1, 3), range(3, 8), range(4, 8));
        RetryAfterProperties.Range range = failure.retryAfter(ranges);
        Set<Long> seen = new HashSet<>();
        for (int attempt = 0; attempt < 200; attempt++) {
            seen.add(Long.parseLong(range.pickSeconds()));
        }
        assertThat(seen).hasSizeGreaterThan(1)
                .allSatisfy(seconds -> assertThat(seconds).isBetween(range.min().toSeconds(), range.max().toSeconds()));
    }

    private static RetryAfterProperties.Range range(long minSeconds, long maxSeconds) {
        return new RetryAfterProperties.Range(Duration.ofSeconds(minSeconds), Duration.ofSeconds(maxSeconds));
    }
}
