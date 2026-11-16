package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.QueryPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Every login and signup looks an account up by email, through the unique key (crud-gen tests.md row 28). */
class UserEmailIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("A lookup by the stored email is planned on uq_users_email, never a sequential scan")
    void emailLookupIsPlannedOnTheUniqueKey() {
        String plan = QueryPlan.explain(jdbc, transactionManager, "select id from users where email = :email",
                new MapSqlParameterSource("email", "someone@example.com"));

        assertThat(QueryPlan.indexExists(jdbc, "uq_users_email")).isTrue();
        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("uq_users_email");
    }
}
