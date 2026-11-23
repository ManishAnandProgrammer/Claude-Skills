package com.example.app.media;

import com.example.app.media.repository.MediaFileRepository;
import com.example.app.support.IntegrationTest;
import com.example.app.support.QueryPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MediaFileIndexIntegrationTest extends IntegrationTest {

    private static final int BATCH = 20;

    @Autowired
    private PlatformTransactionManager transactionManager;

    static Stream<Arguments> jobQueries() {
        return Stream.of(
                Arguments.of("verification", "idx_media_files_attached_upload_expires_at_id", MediaFileRepository.LEASE_FOR_VERIFICATION),
                Arguments.of("cleanup, released", "idx_media_files_released_upload_expires_at_id", MediaFileRepository.LEASE_RELEASED),
                Arguments.of("cleanup, expired unclaimed", "idx_media_files_unclaimed_claimable_until_id", MediaFileRepository.LEASE_EXPIRED_UNCLAIMED));
    }

    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("jobQueries")
    @DisplayName("Every job's lease is an init plan, has its partial index and is planned on it")
    void everyJobQueryIsPlannedOnItsPartialIndex(String name, String index, String statement) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("leaseSeconds", 300L)
                .addValue("batchSize", BATCH);
        // tenancy-row:begin
        // parameters.addValue("tenantId", testTenant().id());
        // tenancy-row:end

        String plan = QueryPlan.explain(jdbc, transactionManager, statement, parameters);

        assertThat(statement).contains("WHERE id = ANY (ARRAY(SELECT id").doesNotContain(" IN (SELECT");
        assertThat(QueryPlan.indexExists(jdbc, index)).as(index).isTrue();
        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains(index);
    }
}
