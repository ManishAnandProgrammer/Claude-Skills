package com.example.app.user;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every login and signup looks an account up by email: that lookup must be able to use the index. */
class UserEmailIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("A lookup by the stored email can use uq_users_email")
    void emailLookupCanUseTheUniqueIndex() {
        // A small test table makes a sequential scan the cheaper plan; switched off, the planner
        // still picks one when no index serves "email = ?", which is the defect this guards against.
        List<String> plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return jdbc.queryForList("EXPLAIN SELECT id FROM users WHERE email = ?", String.class,
                    uniqueEmail("indexed"));
        });

        assertThat(String.join("\n", plan)).contains("uq_users_email");
    }
}
