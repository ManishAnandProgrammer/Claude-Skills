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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RefusedWriteLogIntegrationTest extends IntegrationTest {

    private static final String CONSTRAINT = "uq_people_email";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final CapturedLog log = new CapturedLog();

    @Value("${logging.level.org.hibernate.orm.jdbc.error}")
    private String hibernateErrorLevel;

    @BeforeEach
    void captureTheLog() {
        log.open();
    }

    @AfterEach
    void stopCapturing() {
        log.close();
    }

    @Test
    @DisplayName("Hibernate's own lines about a refused statement are off: the handler logs what is worth a line")
    void hibernatesErrorLinesAreOff() {
        assertThat(hibernateErrorLevel).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("A refused single statement is logged with its constraint, never the value it refused")
    void aRefusedStatementLogsNoValue() {
        String email = "refused-" + UUID.randomUUID() + "@example.test";

        Throwable refused = catchThrowable(() -> inTemporaryPeopleTable(email, () ->
                entityManager.createNativeQuery("insert into people (email) values (:email)")
                        .setParameter("email", email).executeUpdate()));

        assertThat(messagesOf(refused)).contains(CONSTRAINT).doesNotContain(email);
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

        assertThat(messagesOf(refused)).contains(CONSTRAINT).doesNotContain(email);
        assertThat(log.text()).contains(CONSTRAINT).doesNotContain(email);
    }

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

    private static String messagesOf(Throwable refused) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = refused; Objects.nonNull(cause); cause = cause.getCause()) {
            messages.add(cause.getMessage());
            if (cause instanceof SQLException failure) {
                for (SQLException next = failure.getNextException(); Objects.nonNull(next); next = next.getNextException()) {
                    messages.add(next.getMessage());
                }
            }
        }
        return String.join("\n", messages);
    }
}
