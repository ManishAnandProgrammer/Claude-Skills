package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Every login and signup looks an account up by email: one row, through the unique key (crud-gen tests.md row 28). */
class UserEmailIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("A lookup by the stored email has uq_users_email and reads at most the one account")
    void emailLookupReadsOneRow() throws Exception {
        String email = newUser().email();

        ReadCost cost = ReadCost.of(jdbc, transactionManager, "select id from users where email = :email",
                new MapSqlParameterSource("email", email));

        assertThat(ReadCost.indexExists(jdbc, "uq_users_email")).isTrue();
        assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(1);
    }
}
