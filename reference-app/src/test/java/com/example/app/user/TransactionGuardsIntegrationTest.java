package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.TransactionGuards;
import com.example.app.user.domain.User;
import com.example.app.user.domain.UserStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project's change to the transaction setup (its own transaction manager, a client bean of its own type) could
 * switch a guard off, and every other test would stay green.
 */
class TransactionGuardsIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CompromisedPasswordChecker compromisedPasswordChecker;

    @Test
    @DisplayName("A change made inside a read-only transaction is reported, and it really is dropped")
    void aChangeInAReadOnlyTransactionIsReported() throws Exception {
        TestUser user = newUser();
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        readOnly.executeWithoutResult(status -> entityManager.find(User.class, user.id()).changeStatus(UserStatus.SUSPENDED));

        List<String> reported = TransactionGuards.takeViolations();
        assertThat(reported).singleElement().asString().contains("read-only transaction ended with changes");
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, user.id())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("A read-only transaction that changes nothing is not reported")
    void aReadOnlyTransactionWithoutChangesIsNotReported() throws Exception {
        TestUser user = newUser();
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        readOnly.executeWithoutResult(status -> entityManager.find(User.class, user.id()).isActive());

        assertThat(TransactionGuards.takeViolations()).isEmpty();
    }

    @Test
    @DisplayName("A write transaction opened while a GET request is served is reported")
    void aWriteTransactionInAGetRequestIsReported() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("GET", "/api/v1/users/me")));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.queryForObject("select 1", Integer.class));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        assertThat(TransactionGuards.takeViolations()).singleElement().asString().contains("a GET request opened a write transaction");
    }

    @Test
    @DisplayName("A remote call inside a transaction is reported; the same call outside one is not")
    void aRemoteCallInsideATransactionIsReported() {
        compromisedPasswordChecker.check("Outside-A-Transaction-1");
        assertThat(TransactionGuards.takeViolations()).isEmpty();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> compromisedPasswordChecker.check("Inside-A-Transaction-1"));

        assertThat(TransactionGuards.takeViolations()).singleElement().asString().contains("called inside a transaction");
    }
}
