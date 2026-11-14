package com.example.app.metadata;

import com.example.app.metadata.api.Counters;
import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gapless counters: each number handed out once, in order, and a rolled-back one handed out again. */
class CounterIntegrationTest extends IntegrationTest {

    private static final int RACERS = 8;

    @Autowired
    private Counters counters;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String newCounterName() {
        return "TEST_COUNTER_" + UUID.randomUUID().toString().replace("-", "_").toUpperCase();
    }

    /** One caller's business transaction that takes a number and commits. */
    private long nextCommitted(String name) {
        return new TransactionTemplate(transactionManager).execute(status -> counters.next(name));
    }

    @Test
    @DisplayName("A new counter hands out lastValue + 1, then one more per call; the row's version and updatedAt follow")
    void numbersAreSequential() {
        String receipts = newCounterName();
        String admissions = newCounterName();
        counters.create(receipts, 0);
        counters.create(admissions, 999);
        Map<String, Object> before = jdbc.queryForMap("select version, updated_at from counters where key = ?", receipts);

        assertThat(List.of(nextCommitted(receipts), nextCommitted(receipts), nextCommitted(receipts))).containsExactly(1L, 2L, 3L);
        assertThat(nextCommitted(admissions)).isEqualTo(1000L);

        Map<String, Object> after = jdbc.queryForMap("select version, last_value, updated_at from counters where key = ?", receipts);
        assertThat(after.get("last_value")).isEqualTo(3L);
        assertThat(after.get("version")).isEqualTo((Long) before.get("version") + 3);
        assertThat((Timestamp) after.get("updated_at")).isAfterOrEqualTo((Timestamp) before.get("updated_at"));
    }

    @Test
    @DisplayName("An increment inside a signed-in request records that user in updated_by")
    void incrementRecordsTheSignedInUser() {
        String name = newCounterName();
        counters.create(name, 0);
        UUID userId = UUID.randomUUID();
        Jwt token = Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        try {
            assertThat(nextCommitted(name)).isEqualTo(1L);
        } finally {
            SecurityContextHolder.clearContext();
        }

        UUID updatedBy = jdbc.queryForObject("select updated_by from counters where key = ?", UUID.class, name);
        assertThat(updatedBy).isEqualTo(userId);
    }

    @Test
    @DisplayName("Concurrent callers get distinct numbers with no gap: exactly 1..N")
    void concurrentIncrementsAreDistinctAndGapless() throws Exception {
        String name = newCounterName();
        counters.create(name, 0);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> pending = new ArrayList<>();

        List<Long> numbers = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int racer = 0; racer < RACERS; racer++) {
                Callable<Long> take = () -> {
                    start.await();
                    return nextCommitted(name);
                };
                pending.add(executor.submit(take));
            }
            start.countDown();
            for (Future<Long> number : pending) {
                numbers.add(number.get());
            }
        }

        assertThat(numbers).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, RACERS).boxed().toList());
    }

    @Test
    @DisplayName("A number taken in a transaction that rolls back is handed out again: no gap")
    void rolledBackIncrementReturnsItsNumber() {
        String name = newCounterName();
        counters.create(name, 41);

        Long rolledBack = new TransactionTemplate(transactionManager).execute(status -> {
            long number = counters.next(name);
            status.setRollbackOnly();
            return number;
        });

        assertThat(rolledBack).isEqualTo(42L);
        assertThat(nextCommitted(name)).isEqualTo(42L);
        assertThat(nextCommitted(name)).isEqualTo(43L);
    }

    @Test
    @DisplayName("next outside a transaction is refused: its number would commit alone and a failing caller would leave a gap")
    void nextRequiresTheCallersTransaction() {
        String name = newCounterName();
        counters.create(name, 0);

        assertThatThrownBy(() -> counters.next(name)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(nextCommitted(name)).isEqualTo(1L);
    }

    @Test
    @DisplayName("A counter that was never created is a bug, named in the failure; one created twice is refused")
    void missingAndDuplicateCountersFail() {
        String name = newCounterName();

        assertThatThrownBy(() -> nextCommitted(name))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(name);
        counters.create(name, 0);
        assertThatThrownBy(() -> counters.create(name, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(nextCommitted(name)).isEqualTo(1L);
    }
}
