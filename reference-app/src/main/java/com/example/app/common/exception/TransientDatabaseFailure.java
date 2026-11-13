package com.example.app.common.exception;

import com.example.app.common.web.RetryAfterProperties;
import org.hibernate.QueryTimeoutException;
import org.hibernate.TransactionException;
import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.exception.LockAcquisitionException;
import org.hibernate.exception.LockTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Objects;
import java.util.Optional;

/**
 * A database failure that says nothing about the request and may not happen again: answered 503
 * with a jittered {@code Retry-After}, not the 500 a bug gets, so clients back off and retry.
 *
 * <p>Recognised by walking the cause chain: the same timeout reaches the handler as Spring's
 * {@code QueryTimeoutException} from a repository but as a bare {@code PersistenceException} from
 * the {@code EntityManager}, with Hibernate's type underneath both. A connection that broke under
 * the request shows up as an {@code SQLException} with no SQLSTATE (the pool or driver refused) or
 * one of class {@code 08}; a constraint failure carries {@code 23xxx} and stays a 500.</p>
 *
 * <p>The {@code Retry-After} range per kind is configured ({@code app.retry-after.*}).</p>
 */
enum TransientDatabaseFailure {

    /** Waited past {@code lock_timeout}, or lost a deadlock: another request holds the rows. */
    CONTENTION(ErrorCode.DATABASE_CONTENTION),

    /** Ran past {@code statement_timeout}: overload, or a query too costly for its data. */
    QUERY_TIMEOUT(ErrorCode.QUERY_TIMEOUT),

    /** No connection within {@code connection-timeout}, the database is unreachable, or the connection broke. */
    UNAVAILABLE(ErrorCode.DATABASE_UNAVAILABLE);

    private static final String CONNECTION_EXCEPTION_CLASS = "08";

    private final ErrorCode errorCode;

    TransientDatabaseFailure(ErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    static Optional<TransientDatabaseFailure> of(Throwable failure) {
        for (Throwable cause = failure; Objects.nonNull(cause); cause = cause.getCause()) {
            switch (cause) {
                case LockTimeoutException _, LockAcquisitionException _, PessimisticLockingFailureException _ -> {
                    return Optional.of(CONTENTION);
                }
                case QueryTimeoutException _, org.springframework.dao.QueryTimeoutException _ -> {
                    return Optional.of(QUERY_TIMEOUT);
                }
                case SQLTransientConnectionException _, JDBCConnectionException _, DataAccessResourceFailureException _,
                     TransientDataAccessResourceException _, CannotCreateTransactionException _ -> {
                    return Optional.of(UNAVAILABLE);
                }
                case TransactionException transaction when transaction.getCause() instanceof SQLException sql
                        && isConnectionLost(sql) -> {
                    return Optional.of(UNAVAILABLE);
                }
                case SQLException sql when isConnectionException(sql) -> {
                    return Optional.of(UNAVAILABLE);
                }
                default -> {
                    // not this one; look at what it wraps
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isConnectionException(SQLException failure) {
        return Objects.nonNull(failure.getSQLState()) && failure.getSQLState().startsWith(CONNECTION_EXCEPTION_CLASS);
    }

    private static boolean isConnectionLost(SQLException failure) {
        return Objects.isNull(failure.getSQLState()) || isConnectionException(failure);
    }

    ErrorCode errorCode() {
        return errorCode;
    }

    RetryAfterProperties.Range retryAfter(RetryAfterProperties retryAfter) {
        return switch (this) {
            case CONTENTION -> retryAfter.databaseContention();
            case QUERY_TIMEOUT -> retryAfter.queryTimeout();
            case UNAVAILABLE -> retryAfter.databaseUnavailable();
        };
    }
}
