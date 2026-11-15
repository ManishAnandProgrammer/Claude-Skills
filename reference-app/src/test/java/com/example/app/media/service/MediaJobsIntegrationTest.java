package com.example.app.media.service;

import com.example.app.media.dto.internal.MediaFileKey;
import com.example.app.support.IntegrationTest;
import com.example.app.support.MediaStorageContainer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two jobs against a real S3-compatible store. Rows are written directly (purpose {@code JOB_TEST},
 * which the jobs never read), so these tests need no purpose of the example. The jobs work on every
 * due row of the shared database; assertions look at this test's rows only.
 */
class MediaJobsIntegrationTest extends IntegrationTest {

    private static final String PAST = "now() - interval '10 minutes'";
    private static final String FUTURE = "now() + interval '10 minutes'";
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private MediaVerificationJob verificationJob;

    @Autowired
    private MediaCleanupJob cleanupJob;

    @Autowired
    private MediaFileService mediaFiles;

    @Autowired
    private MediaStorageContainer storage;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${app.media.bucket}")
    private String bucket;

    private record TestFile(UUID id, String key) {
    }

    /** A row in {@code status}; the times are SQL expressions. */
    private TestFile insertFile(String status, String uploadExpiresAt, String expiresAt) {
        UUID id = UUID.randomUUID();
        String key = "JOB_TEST/" + UUID.randomUUID();
        // tenancy-row:begin
        // // Row mode: every insert names its tenant (tenant_id has no default).
        // sql("""
        //         insert into media_files (id, tenant_id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by,
        //                                  upload_expires_at, expires_at, created_at, updated_at, version)
        //         values (?, ?, ?, 'JOB_TEST', 'a.pdf', 'application/pdf', 1, ?, ?, %s, %s, now(), now(), 0)
        //         """.formatted(uploadExpiresAt, expiresAt), id, testTenant().id(), key, status, UUID.randomUUID());
        // tenancy-row:else
        sql("""
                insert into media_files (id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by,
                                         upload_expires_at, expires_at, created_at, updated_at, version)
                values (?, ?, 'JOB_TEST', 'a.pdf', 'application/pdf', 1, ?, ?, %s, %s, now(), now(), 0)
                """.formatted(uploadExpiresAt, expiresAt), id, key, status, UUID.randomUUID());
        // tenancy-row:end
        return new TestFile(id, key);
    }

    private void putObject(String key) {
        try (S3Client s3 = storage.client()) {
            s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString("x"));
        }
    }

    private boolean objectExists(String key) {
        try (S3Client s3 = storage.client()) {
            s3.headObject(request -> request.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException absent) {
            return false;
        }
    }

    private String statusOf(TestFile file) {
        List<String> statuses = jdbc.queryForList("select status from media_files where id = ?", String.class, file.id());
        return statuses.isEmpty() ? "DELETED" : statuses.getFirst();
    }

    @Test
    @DisplayName("Verification: past its upload URL's expiry, a file in the store is UPLOADED, an absent one MISSING (counted)")
    void verificationRecordsWhatArrived() {
        TestFile arrived = insertFile("IN_REVIEW", PAST, FUTURE);
        TestFile neverArrived = insertFile("IN_REVIEW", PAST, FUTURE);
        putObject(arrived.key());
        double missingBefore = meterRegistry.counter("media.files.missing").count();

        verificationJob.verify();

        assertThat(statusOf(arrived)).isEqualTo("UPLOADED");
        assertThat(statusOf(neverArrived)).isEqualTo("MISSING");
        assertThat(meterRegistry.counter("media.files.missing").count()).isGreaterThanOrEqualTo(missingBefore + 1);
        assertThat(jdbc.queryForObject("select lease_until from media_files where id = ?", Object.class, neverArrived.id())).isNull();
    }

    @Test
    @DisplayName("Verification waits for the upload URL to expire, and touches no file in another status")
    void verificationWaitsForTheUploadUrlToExpire() {
        TestFile stillUploadable = insertFile("IN_REVIEW", FUTURE, FUTURE);
        TestFile pending = insertFile("PENDING", PAST, FUTURE);
        TestFile released = insertFile("RELEASED", PAST, FUTURE);

        verificationJob.verify();

        assertThat(statusOf(stillUploadable)).isEqualTo("IN_REVIEW");
        assertThat(statusOf(pending)).isEqualTo("PENDING");
        assertThat(statusOf(released)).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("Cleanup deletes released files and never-attached ones past their window, from the store and the table")
    void cleanupDeletesReleasedAndExpiredPending() {
        TestFile released = insertFile("RELEASED", PAST, FUTURE);
        TestFile expiredPending = insertFile("PENDING", "now() - interval '2 hours'", PAST);
        putObject(released.key());
        putObject(expiredPending.key());

        cleanupJob.cleanUp();

        assertThat(statusOf(released)).isEqualTo("DELETED");
        assertThat(statusOf(expiredPending)).isEqualTo("DELETED");
        assertThat(objectExists(released.key())).isFalse();
        assertThat(objectExists(expiredPending.key())).isFalse();
    }

    @Test
    @DisplayName("Cleanup keeps attached files (MISSING too), claimable PENDING ones, and released ones whose URL still accepts a PUT")
    void cleanupKeepsEverythingElse() {
        TestFile claimable = insertFile("PENDING", PAST, FUTURE);
        TestFile releasedStillUploadable = insertFile("RELEASED", FUTURE, FUTURE);
        TestFile missing = insertFile("MISSING", PAST, PAST);
        TestFile uploaded = insertFile("UPLOADED", PAST, PAST);
        TestFile inReview = insertFile("IN_REVIEW", PAST, PAST);
        putObject(claimable.key());
        putObject(uploaded.key());

        cleanupJob.cleanUp();

        assertThat(statusOf(claimable)).isEqualTo("PENDING");
        assertThat(statusOf(releasedStillUploadable)).isEqualTo("RELEASED");
        assertThat(statusOf(missing)).isEqualTo("MISSING");
        assertThat(statusOf(uploaded)).isEqualTo("UPLOADED");
        assertThat(statusOf(inReview)).isEqualTo("IN_REVIEW");
        assertThat(objectExists(claimable.key())).isTrue();
        assertThat(objectExists(uploaded.key())).isTrue();
    }

    @Test
    @DisplayName("A leased file waits for its lease to end; a crashed instance's expired lease is taken over")
    void leasedFilesWaitForTheLease() {
        TestFile leased = insertFile("IN_REVIEW", PAST, FUTURE);
        sql("update media_files set lease_until = now() + interval '5 minutes' where id = ?", leased.id());

        verificationJob.verify();
        assertThat(statusOf(leased)).isEqualTo("IN_REVIEW");

        sql("update media_files set lease_until = now() - interval '1 second' where id = ?", leased.id());
        verificationJob.verify();
        assertThat(statusOf(leased)).isEqualTo("MISSING");
    }

    /**
     * The first lease is held open in its transaction while the second runs: SKIP LOCKED passes over its
     * rows instead of waiting for them. Without it the second would wait past lock_timeout and fail.
     * The rows expired long before any other test's, so they head the queue.
     */
    @Test
    @DisplayName("Two instances leasing at the same time get disjoint batches")
    void concurrentLeasesAreDisjoint() throws Exception {
        for (int index = 0; index < 4; index++) {
            insertFile("IN_REVIEW", "timestamptz '2000-01-01' + interval '%d seconds'".formatted(index), FUTURE);
        }
        CountDownLatch leased = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = newExecutor()) {
            Future<List<MediaFileKey>> first = executor.submit(() -> transaction.execute(status -> {
                List<MediaFileKey> batch = mediaFiles.leaseForVerification(2, LEASE);
                leased.countDown();
                awaitQuietly(release);
                return batch;
            }));
            awaitLatch(leased);
            List<MediaFileKey> second;
            try {
                second = mediaFiles.leaseForVerification(2, LEASE);
            } finally {
                release.countDown();
            }
            List<MediaFileKey> firstBatch = Objects.requireNonNull(within(first));

            assertThat(firstBatch).hasSize(2);
            assertThat(second).hasSize(2).doesNotContainAnyElementsOf(firstBatch);
        }
    }

    /**
     * More due rows than the batch, among a few hundred unrelated ones, so the planner sees a table worth
     * joining: the lease's batch must still be exactly its size (MediaFileRepository's comment on the
     * leases). Rolled back, so the rows never reach a job.
     */
    @Test
    @DisplayName("One lease takes exactly its batch, never more, with more due rows than the batch")
    void aLeaseTakesExactlyItsBatch() {
        for (int index = 0; index < 300; index++) {
            insertFile("UPLOADED", PAST, FUTURE);
        }
        for (int index = 0; index < 6; index++) {
            insertFile("IN_REVIEW", "timestamptz '2000-01-01' + interval '%d seconds'".formatted(index), FUTURE);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.execute("analyze media_files"));

        List<MediaFileKey> batch = new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            return mediaFiles.leaseForVerification(2, LEASE);
        });

        assertThat(batch).hasSize(2);
    }
}
