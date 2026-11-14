package com.example.app.common.exception;

import com.example.app.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

/**
 * A constraint Hibernate can't name (an exclusion constraint) still maps to the code a module
 * declared for it. The table is temporary: it lives only in the test's own transaction.
 */
class ConstraintNamesIntegrationTest extends IntegrationTest {

    private static final String EXCLUSION = "ex_bookings_during";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("An exclusion violation, which Hibernate leaves unnamed, answers the code declared for its constraint")
    void exclusionConstraintAnswersItsDeclaredCode() {
        ConstraintViolationException violation = overlappingBooking();
        assertThat(violation.getSQLState()).isEqualTo("23P01");
        assertThat(violation.getConstraintName()).isNull();
        DataIntegrityViolationException refused = new DataIntegrityViolationException("refused", violation);
        ConstraintErrorCodes bookings = () -> Map.of(EXCLUSION, ErrorCode.DUPLICATE_RECORD);

        assertThat(new ConstraintViolationCodes(List.of(bookings)).codeFor(refused)).contains(ErrorCode.DUPLICATE_RECORD);
        assertThat(new ConstraintViolationCodes(List.of()).codeFor(refused)).isEmpty();
    }

    /** Hibernate's own exception for a real exclusion violation, as Spring receives it. */
    private ConstraintViolationException overlappingBooking() {
        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("""
                    create temporary table bookings (
                        during tstzrange not null,
                        constraint ex_bookings_during exclude using gist (during with &&)
                    ) on commit drop""").executeUpdate();
            entityManager.createNativeQuery("insert into bookings values (tstzrange('2026-01-01', '2026-01-03'))").executeUpdate();
            entityManager.createNativeQuery("insert into bookings values (tstzrange('2026-01-02', '2026-01-04'))").executeUpdate();
        }));
        for (Throwable cause = failure; Objects.nonNull(cause); cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation;
            }
        }
        return fail("no constraint violation in " + failure);
    }
}
