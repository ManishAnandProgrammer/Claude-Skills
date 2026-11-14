package com.example.app.common.exception;

import jakarta.persistence.PersistenceException;
import org.hibernate.QueryTimeoutException;
import org.hibernate.exception.LockTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

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
}
