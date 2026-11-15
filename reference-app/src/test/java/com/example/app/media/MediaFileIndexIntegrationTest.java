package com.example.app.media;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each job's lease statement (MediaFileRepository, copied here verbatim) reads its own partial index in
 * order: that index named in the plan and no sort on the expiry column. A sort of the ids alone is fine:
 * the planner may de-duplicate the {@code IN}'s ids with one once other tests have left media rows. Scans
 * that read whole tables are switched off, because on the few rows of a test database they would win on
 * cost and hide a missing index.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaFileIndexIntegrationTest extends IntegrationTest {

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
        //                now() + (n || ' seconds')::interval, now() + interval '1 hour' + (n || ' seconds')::interval,
        //                now(), now(), 0
        //           from generate_series(1, 1000) n
        //         """, testTenant().id());
        // tenancy-row:else
        sql("""
                insert into media_files (id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by,
                                         upload_expires_at, expires_at, created_at, updated_at, version)
                select gen_random_uuid(), 'INDEX_TEST/' || gen_random_uuid(), 'INDEX_TEST', 'a.pdf', 'application/pdf', 1,
                       (array['PENDING', 'IN_REVIEW', 'UPLOADED', 'MISSING', 'RELEASED'])[1 + n % 5], gen_random_uuid(),
                       now() + (n || ' seconds')::interval, now() + interval '1 hour' + (n || ' seconds')::interval,
                       now(), now(), 0
                  from generate_series(1, 1000) n
                """);
        // tenancy-row:end
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.execute("analyze media_files"));
    }

    private static String lease(String status, String expiryColumn) {
        return """
                UPDATE media_files
                SET lease_until = now() + :leaseSeconds * interval '1 second'
                WHERE id IN (SELECT id
                               FROM media_files
                              WHERE status = '%s'
                                AND %s < now()
                                AND (lease_until IS NULL OR lease_until < now())
                              ORDER BY %s, id
                              LIMIT :batchSize
                              FOR UPDATE SKIP LOCKED)
                RETURNING id""".formatted(status, expiryColumn, expiryColumn);
    }

    static Stream<Arguments> jobQueries() {
        return Stream.of(
                Arguments.of("verification", lease("IN_REVIEW", "upload_expires_at"), "idx_media_files_in_review_upload_expires_at_id"),
                Arguments.of("cleanup, released", lease("RELEASED", "upload_expires_at"), "idx_media_files_released_upload_expires_at_id"),
                Arguments.of("cleanup, expired pending", lease("PENDING", "expires_at"), "idx_media_files_pending_expires_at_id"));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("jobQueries")
    @DisplayName("Every job's lease reads its own partial index in order")
    void everyJobQueryReadsItsIndex(String name, String statement, String index) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("leaseSeconds", 300L)
                .addValue("batchSize", 200);

        List<String> planLines = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc).queryForList("explain " + statement, parameters, String.class);
        });
        String plan = String.join("\n", planLines);

        assertThat(plan).as(plan).contains("using " + index + " on").doesNotContainPattern("Sort Key: [^\\n]*expires_at");
    }
}
