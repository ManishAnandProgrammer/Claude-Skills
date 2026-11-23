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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rows are written directly with purpose {@code JOB_TEST}, which the jobs never read, so these tests need no
 * purpose of the example.
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

    private TestFile insertFile(String status, String uploadExpiresAtSql, String claimableUntilSql) {
        UUID id = UUID.randomUUID();
        String key = "JOB_TEST/" + UUID.randomUUID();
        // tenancy-row:begin
        // sql("""
        //         insert into media_files (id, tenant_id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by,
        //                                  upload_expires_at, claimable_until, created_at, updated_at, version)
        //         values (?, ?, ?, 'JOB_TEST', 'a.pdf', 'application/pdf', 1, ?, ?, %s, %s, now(), now(), 0)
        //         """.formatted(uploadExpiresAtSql, claimableUntilSql), id, testTenant().id(), key, status, UUID.randomUUID());
        // tenancy-row:else
        sql("""
                insert into media_files (id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by,
                                         upload_expires_at, claimable_until, created_at, updated_at, version)
                values (?, ?, 'JOB_TEST', 'a.pdf', 'application/pdf', 1, ?, ?, %s, %s, now(), now(), 0)
                """.formatted(uploadExpiresAtSql, claimableUntilSql), id, key, status, UUID.randomUUID());
        // tenancy-row:end
        return new TestFile(id, key);
    }

    private void putObject(String key) {
        try (S3Client s3 = storage.newClient()) {
            s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString("x"));
        }
    }

    private boolean objectExists(String key) {
        try (S3Client s3 = storage.newClient()) {
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
    @DisplayName("Verification: past its upload URL's expiry, a file in the store is VERIFIED, an absent one MISSING (counted)")
    void verificationRecordsWhatArrived() {
        TestFile arrived = insertFile("ATTACHED", PAST, FUTURE);
        TestFile neverArrived = insertFile("ATTACHED", PAST, FUTURE);
        putObject(arrived.key());
        double missingBefore = meterRegistry.counter("media.files.missing").count();

        verificationJob.verify();

        assertThat(statusOf(arrived)).isEqualTo("VERIFIED");
        assertThat(statusOf(neverArrived)).isEqualTo("MISSING");
        assertThat(meterRegistry.counter("media.files.missing").count()).isGreaterThanOrEqualTo(missingBefore + 1);
        assertThat(jdbc.queryForObject("select lease_until from media_files where id = ?", Object.class, neverArrived.id())).isNull();
    }

    @Test
    @DisplayName("Verification waits for the upload URL to expire, and touches no file in another status")
    void verificationWaitsForTheUploadUrlToExpire() {
        TestFile stillUploadable = insertFile("ATTACHED", FUTURE, FUTURE);
        TestFile unclaimed = insertFile("UNCLAIMED", PAST, FUTURE);
        TestFile released = insertFile("RELEASED", PAST, FUTURE);

        verificationJob.verify();

        assertThat(statusOf(stillUploadable)).isEqualTo("ATTACHED");
        assertThat(statusOf(unclaimed)).isEqualTo("UNCLAIMED");
        assertThat(statusOf(released)).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("Cleanup deletes released files and never-attached ones past their window, from the store and the table")
    void cleanupDeletesReleasedAndExpiredUnclaimed() {
        TestFile released = insertFile("RELEASED", PAST, FUTURE);
        TestFile expiredUnclaimed = insertFile("UNCLAIMED", "now() - interval '2 hours'", PAST);
        putObject(released.key());
        putObject(expiredUnclaimed.key());

        cleanupJob.cleanUp();

        assertThat(statusOf(released)).isEqualTo("DELETED");
        assertThat(statusOf(expiredUnclaimed)).isEqualTo("DELETED");
        assertThat(objectExists(released.key())).isFalse();
        assertThat(objectExists(expiredUnclaimed.key())).isFalse();
    }

    @Test
    @DisplayName("Cleanup keeps attached files (MISSING too), claimable UNCLAIMED ones, and released ones whose URL still accepts a PUT")
    void cleanupKeepsEverythingElse() {
        TestFile claimable = insertFile("UNCLAIMED", PAST, FUTURE);
        TestFile releasedStillUploadable = insertFile("RELEASED", FUTURE, FUTURE);
        TestFile missing = insertFile("MISSING", PAST, PAST);
        TestFile verified = insertFile("VERIFIED", PAST, PAST);
        TestFile attached = insertFile("ATTACHED", PAST, PAST);
        putObject(claimable.key());
        putObject(verified.key());

        cleanupJob.cleanUp();

        assertThat(statusOf(claimable)).isEqualTo("UNCLAIMED");
        assertThat(statusOf(releasedStillUploadable)).isEqualTo("RELEASED");
        assertThat(statusOf(missing)).isEqualTo("MISSING");
        assertThat(statusOf(verified)).isEqualTo("VERIFIED");
        assertThat(statusOf(attached)).isEqualTo("ATTACHED");
        assertThat(objectExists(claimable.key())).isTrue();
        assertThat(objectExists(verified.key())).isTrue();
    }

    @Test
    @DisplayName("A leased file waits for its lease to end; a crashed instance's expired lease is taken over")
    void leasedFilesWaitForTheLease() {
        TestFile leased = insertFile("ATTACHED", PAST, FUTURE);
        sql("update media_files set lease_until = now() + interval '5 minutes' where id = ?", leased.id());

        verificationJob.verify();
        assertThat(statusOf(leased)).isEqualTo("ATTACHED");

        sql("update media_files set lease_until = now() - interval '1 second' where id = ?", leased.id());
        verificationJob.verify();
        assertThat(statusOf(leased)).isEqualTo("MISSING");
    }

    /**
     * Without SKIP LOCKED the second lease would wait for the first's held transaction past lock_timeout. The
     * rows expired before any other test's (theirs from 2000 on), so they head the queue: the two batches are
     * exactly these four.
     */
    @Test
    @DisplayName("Two instances leasing at the same time get disjoint batches")
    void concurrentLeasesAreDisjoint() throws Exception {
        List<UUID> inserted = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            inserted.add(insertFile("ATTACHED", "timestamptz '1990-01-01' + interval '%d seconds'".formatted(index), FUTURE).id());
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
            List<MediaFileKey> firstBatch = Objects.requireNonNull(awaitResult(first));

            assertThat(firstBatch).hasSize(2);
            assertThat(second).hasSize(2).doesNotContainAnyElementsOf(firstBatch);
            assertThat(Stream.concat(firstBatch.stream(), second.stream()).map(MediaFileKey::id))
                    .containsExactlyInAnyOrderElementsOf(inserted);
        }
    }

    /** A few hundred unrelated rows, so the planner sees a table worth joining (MediaFileRepository's comment on the leases). */
    @Test
    @DisplayName("One lease takes exactly its batch, never more, with more due rows than the batch")
    void aLeaseTakesExactlyItsBatch() {
        for (int index = 0; index < 300; index++) {
            insertFile("VERIFIED", PAST, FUTURE);
        }
        for (int index = 0; index < 6; index++) {
            insertFile("ATTACHED", "timestamptz '2000-01-01' + interval '%d seconds'".formatted(index), FUTURE);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.execute("analyze media_files"));

        List<MediaFileKey> batch = new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            return mediaFiles.leaseForVerification(2, LEASE);
        });

        assertThat(batch).hasSize(2);
    }
}
