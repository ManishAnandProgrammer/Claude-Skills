package com.example.app.common.persistence;

import com.example.app.common.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The change history's actor: which transactions it names a caller in, and a failure that never escapes it. */
class ChangeHistoryActorTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ChangeHistoryActor actor = new ChangeHistoryActor(jdbcTemplate, currentUser);

    @BeforeEach
    void startSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static TransactionExecution transaction(boolean newTransaction, boolean readOnly) {
        TransactionExecution transaction = mock(TransactionExecution.class);
        when(transaction.isNewTransaction()).thenReturn(newTransaction);
        when(transaction.isReadOnly()).thenReturn(readOnly);
        return transaction;
    }

    @Test
    @DisplayName("A new write transaction of a signed-in caller sets the caller, transaction-locally")
    void aWriteTransactionNamesItsCaller() {
        UUID caller = UUID.randomUUID();
        when(currentUser.findId()).thenReturn(Optional.of(caller));

        actor.afterBegin(transaction(true, false), null);

        verify(jdbcTemplate).queryForObject(ChangeHistoryActor.SET_ACTOR, String.class, caller.toString());
        assertThat(ChangeHistoryActor.SET_ACTOR).contains("true)");
    }

    @Test
    @DisplayName("A read-only transaction, a joined one, one that failed to begin, or no caller sets nothing")
    void otherTransactionsSetNothing() {
        when(currentUser.findId()).thenReturn(Optional.of(UUID.randomUUID()));

        actor.afterBegin(transaction(true, true), null);
        actor.afterBegin(transaction(false, false), null);
        actor.afterBegin(transaction(true, false), new IllegalStateException("no connection"));
        when(currentUser.findId()).thenReturn(Optional.empty());
        actor.afterBegin(transaction(true, false), null);

        verify(jdbcTemplate, never()).queryForObject(eq(ChangeHistoryActor.SET_ACTOR), eq(String.class), any(Object[].class));
    }

    @Test
    @DisplayName("A failure never leaves the listener: it is rethrown at the commit, so the transaction rolls back")
    void aFailureIsRethrownAtTheCommit() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("connection lost");
        when(currentUser.findId()).thenReturn(Optional.of(UUID.randomUUID()));
        when(jdbcTemplate.queryForObject(eq(ChangeHistoryActor.SET_ACTOR), eq(String.class), any(Object[].class))).thenThrow(failure);

        assertThatCode(() -> actor.afterBegin(transaction(true, false), null)).doesNotThrowAnyException();

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        assertThatThrownBy(() -> synchronizations.getFirst().beforeCommit(false)).isSameAs(failure);
    }
}
