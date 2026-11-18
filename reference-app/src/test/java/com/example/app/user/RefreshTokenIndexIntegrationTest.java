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

/**
 * The purge's batch (RefreshTokenRepository's own constant, what the job runs) reads the expiry index, never the whole
 * table, and is computed once (crud-gen tests.md row 28; house-review DATA-10, DATA-29). RefreshTokenPurgeJobIntegrationTest
 * proves which rows go.
 */
class RefreshTokenIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * The batch is {@code = ANY (ARRAY(...))}, an init plan computed once whatever the planner picks: as
     * {@code IN (...)} it may run again per outer row and delete more than one batch. That is the statement's own
     * text, so it is checked as text.
     */
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
