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
 * Tells the database who is writing, for the change history (the change-history piece, house-review DATA-08): at
 * the start of every new write transaction of a signed-in request, {@code set_config('app.user_id', <the caller's
 * id>, true)}, which {@code audit_row_change()} (changeset 0035) records as {@code audit_log.actor_id}.
 *
 * <p>Transaction-local ({@code is_local = true}): PostgreSQL drops the setting at the commit or the rollback, so a
 * pooled connection never carries one request's caller into the next borrower's transaction; never set at session
 * level. A read-only transaction writes no history and is skipped; outside a signed-in request (a signup, a
 * scheduled job) nothing is set and the history's actor is null, as {@code updated_by} is.</p>
 *
 * <p>Spring's own hook: Boot adds every {@link TransactionExecutionListener} bean to the transaction manager, which
 * calls {@code afterBegin} once the transaction's resources are bound, so the statement runs on the transaction's
 * own connection, inside it (the pool's lazy proxy borrows that connection here rather than at the first
 * statement, which follows anyway).</p>
 *
 * <p>Never throws: the transaction's resources are bound by now, and an exception out of a listener would leave
 * them bound to the thread (its connection never returned). A failed statement is rethrown at the commit instead
 * ({@link FailAtCommit}): the transaction rolls back and the caller gets the database's error (a 503 through
 * {@code TransientDatabaseFailure}), never a success whose writes were dropped nor writes without their author.
 * Marking it rollback-only would roll back without a word: the request would answer 2xx.</p>
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
        Optional<UUID> caller = currentUser.findId();
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

    /** Rethrows the actor's failure before the commit, so the transaction rolls back and its caller learns why. */
    record FailAtCommit(DataAccessException failure) implements TransactionSynchronization {

        @Override
        public void beforeCommit(boolean readOnly) {
            throw failure;
        }
    }
}
