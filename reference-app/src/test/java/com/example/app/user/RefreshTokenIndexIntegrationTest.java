package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.QueryPlan;
import com.example.app.user.repository.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("The purge's batch is an init plan over idx_refresh_tokens_expires_at")
    void purgeBatchIsPlannedOnTheExpiryIndex() {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("expiredBefore", Timestamp.from(Instant.now()))
                .addValue("batchSize", 1000);

        String plan = QueryPlan.explain(jdbc, transactionManager, RefreshTokenRepository.DELETE_EXPIRED, parameters);

        assertThat(RefreshTokenRepository.DELETE_EXPIRED).contains("WHERE id = ANY (ARRAY(SELECT id").doesNotContain(" IN (SELECT");
        assertThat(QueryPlan.indexExists(jdbc, "idx_refresh_tokens_expires_at")).isTrue();
        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("idx_refresh_tokens_expires_at");
    }
}
