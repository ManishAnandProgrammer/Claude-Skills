package com.example.app.media.repository;

import com.example.app.media.api.MediaFileStatus;
import com.example.app.media.domain.MediaFile;
import com.example.app.media.dto.internal.MediaFileKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Native statements bypass auditing: they set {@code updated_at}, {@code updated_by} and {@code version}
 * themselves. Times are the database's {@code now()} throughout, so every instance and both jobs compare
 * against one clock.
 */
public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {

    /*
     * The jobs' leases, constants so that MediaFileIndexIntegrationTest plans exactly what the jobs run.
     * Each batch is an ARRAY(...) of the locked ids, which PostgreSQL computes once (an init plan). Written
     * as "id IN (SELECT ... LIMIT ... FOR UPDATE SKIP LOCKED)", the planner may put the subquery on the
     * inner side of a join and run it again per outer row; every rerun skips the rows the earlier runs
     * locked and returns others, so one lease took more rows than its batch.
     */
    String LEASE_FOR_VERIFICATION = """
            UPDATE media_files
            SET lease_until = now() + :leaseSeconds * interval '1 second'
            WHERE id = ANY (ARRAY(SELECT id
                                    FROM media_files
                                   WHERE status = 'ATTACHED'
                                     AND upload_expires_at < now()
                                     AND (lease_until IS NULL OR lease_until < now())
                                   ORDER BY upload_expires_at, id
                                   LIMIT :batchSize
                                   FOR UPDATE SKIP LOCKED))
            RETURNING id""";

    String LEASE_RELEASED = """
            UPDATE media_files
            SET lease_until = now() + :leaseSeconds * interval '1 second'
            WHERE id = ANY (ARRAY(SELECT id
                                    FROM media_files
                                   WHERE status = 'RELEASED'
                                     AND upload_expires_at < now()
                                     AND (lease_until IS NULL OR lease_until < now())
                                   ORDER BY upload_expires_at, id
                                   LIMIT :batchSize
                                   FOR UPDATE SKIP LOCKED))
            RETURNING id""";

    String LEASE_EXPIRED_UNCLAIMED = """
            UPDATE media_files
            SET lease_until = now() + :leaseSeconds * interval '1 second'
            WHERE id = ANY (ARRAY(SELECT id
                                    FROM media_files
                                   WHERE status = 'UNCLAIMED'
                                     AND claimable_until < now()
                                     AND (lease_until IS NULL OR lease_until < now())
                                   ORDER BY claimable_until, id
                                   LIMIT :batchSize
                                   FOR UPDATE SKIP LOCKED))
            RETURNING id""";

    /**
     * The row locks last until the caller's transaction ends, so two records claiming one key queue, and
     * the second finds it ATTACHED.
     */
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET status = 'ATTACHED',
                updated_at = now(),
                updated_by = CAST(:uploadedBy AS uuid),
                version = version + 1
            WHERE key IN (:keys)
              AND purpose = :purpose
              AND uploaded_by = CAST(:uploadedBy AS uuid)
              AND status = 'UNCLAIMED'
              AND claimable_until > now()
            RETURNING id""")
    List<UUID> claim(Collection<String> keys, String purpose, UUID uploadedBy);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET status = 'RELEASED',
                updated_at = now(),
                updated_by = CAST(:updatedBy AS uuid),
                version = version + 1
            WHERE id IN (:ids)
              AND status IN ('ATTACHED', 'VERIFIED', 'MISSING')""")
    int release(Collection<UUID> ids, UUID updatedBy);

    List<MediaFile> findByIdInOrderById(Collection<UUID> ids);

    long countByIdInAndStatus(Collection<UUID> ids, MediaFileStatus status);

    @Query("""
            select new com.example.app.media.dto.internal.MediaFileKey(file.id, file.key)
              from MediaFile file
             where file.id in :ids
             order by file.id""")
    List<MediaFileKey> findKeys(Collection<UUID> ids);

    /**
     * {@code SKIP LOCKED} passes over rows another instance is leasing right now, and {@code lease_until}
     * over the ones it leased before: instances get disjoint batches, and a crashed instance's rows return
     * when its lease runs out. The lease is bookkeeping, not a change of the file: no version or audit columns.
     */
    @Query(nativeQuery = true, value = LEASE_FOR_VERIFICATION)
    List<UUID> leaseForVerification(int batchSize, long leaseSeconds);

    // Both only while the file is still ATTACHED: a removal since wins.
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET status = 'VERIFIED',
                lease_until = NULL,
                updated_at = now(),
                updated_by = NULL,
                version = version + 1
            WHERE id IN (:ids)
              AND status = 'ATTACHED'""")
    int markVerified(Collection<UUID> ids);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET status = 'MISSING',
                lease_until = NULL,
                updated_at = now(),
                updated_by = NULL,
                version = version + 1
            WHERE id IN (:ids)
              AND status = 'ATTACHED'""")
    int markMissing(Collection<UUID> ids);

    @Query(nativeQuery = true, value = LEASE_RELEASED)
    List<UUID> leaseReleased(int batchSize, long leaseSeconds);

    @Query(nativeQuery = true, value = LEASE_EXPIRED_UNCLAIMED)
    List<UUID> leaseExpiredUnclaimed(int batchSize, long leaseSeconds);

    /**
     * Only RELEASED and expired UNCLAIMED rows: neither can change state any more (a claim needs an
     * unexpired UNCLAIMED file), so nothing attached is ever deleted.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM media_files
            WHERE id IN (:ids)
              AND (status = 'RELEASED' OR (status = 'UNCLAIMED' AND claimable_until < now()))""")
    int deleteRemoved(Collection<UUID> ids);
}
