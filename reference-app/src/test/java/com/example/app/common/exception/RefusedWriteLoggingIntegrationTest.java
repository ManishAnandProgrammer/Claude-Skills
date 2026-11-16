package com.example.app.common.exception;

import com.example.app.support.CapturedLog;
import com.example.app.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A write a constraint refuses is logged without the row's values: no bound parameter of the failed
 * statement and no server DETAIL ("Key (email)=(...) already exists") in any log line or logged
 * exception (SEC-04; {@code logServerErrorDetail: false} in {@code config/db.yml}). Every unique, foreign
 * key and check refusal of every entity takes this path, and most of them are ordinary 409s. The table is
 * temporary: it lives only in the test's own transaction.
 */
class RefusedWriteLoggingIntegrationTest extends IntegrationTest {

    private static final String CONSTRAINT = "uq_people_email";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final CapturedLog log = new CapturedLog();

    @BeforeEach
    void captureTheLog() {
        log.open();
    }

    @AfterEach
    void stopCapturing() {
        log.close();
    }

    @Test
    @DisplayName("A refused single statement is logged with its constraint, never the value it refused")
    void aRefusedStatementLogsNoValue() {
        String email = "refused-" + UUID.randomUUID() + "@example.test";

        Throwable refused = catchThrowable(() -> inTemporaryPeopleTable(email, () ->
                entityManager.createNativeQuery("insert into people (email) values (:email)")
                        .setParameter("email", email).executeUpdate()));

        assertThat(refused).isNotNull();
        assertThat(log.text()).contains(CONSTRAINT).doesNotContain(email);
    }

    @Test
    @DisplayName("A refused batch, as Hibernate flushes entities, is logged without its bound values")
    void aRefusedBatchLogsNoValue() {
        String email = "refused-" + UUID.randomUUID() + "@example.test";

        Throwable refused = catchThrowable(() -> inTemporaryPeopleTable(email, () ->
                entityManager.unwrap(Session.class).doWork(connection -> {
                    try (PreparedStatement insert = connection.prepareStatement("insert into people (email) values (?)")) {
                        insert.setString(1, email);
                        insert.addBatch();
                        insert.executeBatch();
                    }
                })));

        assertThat(refused).isNotNull();
        assertThat(log.text()).contains(CONSTRAINT).doesNotContain(email);
    }

    /** Creates the table, stores {@code email} once, then runs the write that repeats it. */
    private void inTemporaryPeopleTable(String email, Runnable repeat) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("""
                    create temporary table people (
                        email text not null,
                        constraint uq_people_email unique (email)
                    ) on commit drop""").executeUpdate();
            entityManager.createNativeQuery("insert into people (email) values (:email)")
                    .setParameter("email", email).executeUpdate();
            repeat.run();
        });
    }
}
