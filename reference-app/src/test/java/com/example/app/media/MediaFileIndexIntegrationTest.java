package com.example.app.media;

import com.example.app.media.repository.MediaFileRepository;
import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each job's lease statement (MediaFileRepository's own constant, what the job runs) has its partial index
 * and reads only its batch (crud-gen tests.md row 28): 1,000 files, a fifth in each status, every one past
 * its expiry. MediaJobsIntegrationTest proves that a lease takes exactly its batch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaFileIndexIntegrationTest extends IntegrationTest {

    /** A batch well below each status's 200 seeded files. */
    private static final int BATCH = 20;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    void seedFilesInEveryStatus() {
        // tenancy-row:begin
        // // Row mode: every insert names its tenant (tenant_id has no default).
        // sql("""
        //         insert into media_files (id, tenant_id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by,
        //                                  upload_expires_at, expires_at, created_at, updated_at, version)
        //         select gen_random_uuid(), ?, 'INDEX_TEST/' || gen_random_uuid(), 'INDEX_TEST', 'a.pdf', 'application/pdf', 1,
        //                (array['PENDING', 'IN_REVIEW', 'UPLOADED', 'MISSING', 'RELEASED'])[1 + n % 5], gen_random_uuid(),
        //                now() - interval '1 day' + (n || ' seconds')::interval, now() - interval '1 day' + (n || ' seconds')::interval,
        //                now(), now(), 0
        //           from generate_series(1, 1000) n
        //         """, testTenant().id());
        // tenancy-row:else
        sql("""
                insert into media_files (id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by,
                                         upload_expires_at, expires_at, created_at, updated_at, version)
                select gen_random_uuid(), 'INDEX_TEST/' || gen_random_uuid(), 'INDEX_TEST', 'a.pdf', 'application/pdf', 1,
                       (array['PENDING', 'IN_REVIEW', 'UPLOADED', 'MISSING', 'RELEASED'])[1 + n % 5], gen_random_uuid(),
                       now() - interval '1 day' + (n || ' seconds')::interval, now() - interval '1 day' + (n || ' seconds')::interval,
                       now(), now(), 0
                  from generate_series(1, 1000) n
                """);
        // tenancy-row:end
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.execute("analyze media_files"));
    }

    /** Every seeded file is past its expiry: left behind, the jobs other classes run would lease them. */
    @AfterAll
    void deleteTheSeededFiles() {
        sql("delete from media_files where purpose = 'INDEX_TEST'");
    }

    static Stream<Arguments> jobQueries() {
        return Stream.of(
                Arguments.of("verification", "idx_media_files_in_review_upload_expires_at_id", MediaFileRepository.LEASE_FOR_VERIFICATION),
                Arguments.of("cleanup, released", "idx_media_files_released_upload_expires_at_id", MediaFileRepository.LEASE_RELEASED),
                Arguments.of("cleanup, expired pending", "idx_media_files_pending_expires_at_id", MediaFileRepository.LEASE_EXPIRED_PENDING));
    }

    /**
     * A lease reads its batch from its partial index and updates those rows by id: twice the batch in all;
     * walked from another index it filters the other statuses' rows, far more than its bound, as every seeded
     * file is past its expiry. Run and rolled back. The batch is {@code = ANY (ARRAY(...))}, an init plan
     * computed once whatever the planner picks: as {@code IN (...)} it may become the inner side of a
     * semi-join that runs again and leases more than the batch (house-review DATA-29). That is the
     * statement's own text, so it is checked as text: on this seed the planner happens to run {@code IN}
     * once too, and no bound tells the two apart.
     */
    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("jobQueries")
    @DisplayName("Every job's lease is an init plan, has its partial index and reads only its batch, twice")
    void everyJobQueryReadsOnlyItsBatch(String name, String index, String statement) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("leaseSeconds", 300L)
                .addValue("batchSize", BATCH);
        // tenancy-row:begin
        // parameters.addValue("tenantId", testTenant().id());
        // tenancy-row:end

        ReadCost cost = ReadCost.of(jdbc, transactionManager, statement, parameters);

        assertThat(statement).contains("WHERE id = ANY (ARRAY(SELECT id").doesNotContain(" IN (SELECT");
        assertThat(ReadCost.indexExists(jdbc, index)).as(index).isTrue();
        assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(2L * 2 * BATCH);
    }
}
