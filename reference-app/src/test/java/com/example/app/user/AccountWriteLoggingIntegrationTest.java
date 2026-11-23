package com.example.app.user;

import com.example.app.support.CapturedLog;
import com.example.app.support.IntegrationTest;
import com.example.app.user.domain.User;
import com.example.app.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The insert skips signup's existence check, the path a racing signup takes; what keeps the values out is
 * {@code logServerErrorDetail: false} in {@code config/db.yml}.
 */
class AccountWriteLoggingIntegrationTest extends IntegrationTest {

    private static final String FULL_NAME = "Racing Signup Person";
    private static final String PASSWORD_HASH = "$2a$10$racingSignupHashThatMustNeverBeLoggedXyz0123456789abcd";

    @Autowired
    private UserRepository users;

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
    @DisplayName("An insert refused by the address's unique key logs the constraint, never the account's values")
    void aRefusedAccountInsertLogsNoPersonalData() throws Exception {
        TestUser taken = newUser();

        Throwable refused = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                users.saveAndFlush(User.register(taken.email(), FULL_NAME, PASSWORD_HASH))));

        assertThat(refused).isNotNull();
        assertThat(log.text()).contains("uq_users_email")
                .doesNotContain(taken.email())
                .doesNotContain(FULL_NAME)
                .doesNotContain(PASSWORD_HASH);
    }
}
