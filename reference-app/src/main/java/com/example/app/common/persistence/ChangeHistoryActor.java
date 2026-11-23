package com.example.app.common.persistence;

import com.example.app.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transaction-local ({@code is_local = true}): PostgreSQL drops the setting at the commit or the rollback, so a
 * pooled connection never carries one request's caller into the next borrower's transaction; never set at session
 * level. Boot adds every {@link TransactionExecutionListener} bean to the transaction manager, which calls
 * {@code afterBegin} once the transaction's resources are bound, so the statement runs on the transaction's own
 * connection.
 *
 * <p>Never throws: an exception out of a listener would leave the transaction's resources bound to the thread (its
 * connection never returned). A failed statement is rethrown at the commit instead ({@link FailAtCommit}), so the
 * transaction rolls back; marking it rollback-only would roll back without a word, and the request would answer
 * 2xx.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeHistoryActor implements TransactionExecutionListener {

    static final String SET_ACTOR = "select set_config('app.user_id', ?, true)";

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUser currentUser;

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (Objects.nonNull(beginFailure) || !transaction.isNewTransaction() || transaction.isReadOnly()) {
            return;
        }
        // tenancy:begin
        // // An impersonating staff member is the one who writes (the token's act claim), not the member they act as.
        // Optional<UUID> caller = currentUser.actorId().or(currentUser::findId);
        // tenancy:else
        Optional<UUID> caller = currentUser.findId();
        // tenancy:end
        if (caller.isEmpty()) {
            return;
        }
        try {
            jdbcTemplate.queryForObject(SET_ACTOR, String.class, caller.get().toString());
        } catch (DataAccessException failed) {
            log.warn("The change history's actor couldn't be set, the transaction fails at its commit: cause={}", failed.toString());
            TransactionSynchronizationManager.registerSynchronization(new FailAtCommit(failed));
        }
    }

    record FailAtCommit(DataAccessException failure) implements TransactionSynchronization {

        @Override
        public void beforeCommit(boolean readOnly) {
            throw failure;
        }
    }
}
