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
 * Every status change is one conditional statement, so a claim, a removal and a job never act on a
 * state another one already changed. Native statements bypass auditing: they set {@code updated_at},
 * {@code updated_by} and {@code version} themselves. Times are the database's {@code now()} throughout,
 * so every instance and both jobs compare against one clock.
 *
 * <p>Row mode: Hibernate's tenant filter never reaches a native statement, so each one names its tenant
 * ({@code tenant_id = :tenantId}, once per table it reads or writes) and the service passes the current
 * one, a job's included (the tenant it runs for). The policy is the fallback, not the filter: a platform
 * statement and a tenant statement never look alike, and the planner sees the tenant as a parameter
 * (house-review TEN-37).</p>
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
                                   WHERE status = 'IN_REVIEW'
                                     AND upload_expires_at < now()
                                     AND (lease_until IS NULL OR lease_until < now())
                                     AND tenant_id = :tenantId
                                   ORDER BY upload_expires_at, id
                                   LIMIT :batchSize
                                   FOR UPDATE SKIP LOCKED))
              AND tenant_id = :tenantId
            RETURNING id""";

    String LEASE_RELEASED = """
            UPDATE media_files
            SET lease_until = now() + :leaseSeconds * interval '1 second'
            WHERE id = ANY (ARRAY(SELECT id
                                    FROM media_files
                                   WHERE status = 'RELEASED'
                                     AND upload_expires_at < now()
                                     AND (lease_until IS NULL OR lease_until < now())
                                     AND tenant_id = :tenantId
                                   ORDER BY upload_expires_at, id
                                   LIMIT :batchSize
                                   FOR UPDATE SKIP LOCKED))
              AND tenant_id = :tenantId
            RETURNING id""";

    String LEASE_EXPIRED_PENDING = """
            UPDATE media_files
            SET lease_until = now() + :leaseSeconds * interval '1 second'
            WHERE id = ANY (ARRAY(SELECT id
                                    FROM media_files
                                   WHERE status = 'PENDING'
                                     AND expires_at < now()
                                     AND (lease_until IS NULL OR lease_until < now())
                                     AND tenant_id = :tenantId
                                   ORDER BY expires_at, id
                                   LIMIT :batchSize
                                   FOR UPDATE SKIP LOCKED))
              AND tenant_id = :tenantId
            RETURNING id""";

    /**
     * PENDING to IN_REVIEW for the keys that are the caller's, of this purpose, never claimed and still
     * claimable; the others are left alone. The row locks last until the caller's transaction ends, so
     * two records claiming one key queue, and the second finds it IN_REVIEW.
     */
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET status = 'IN_REVIEW',
                updated_at = now(),
                updated_by = CAST(:uploadedBy AS uuid),
                version = version + 1
            WHERE key IN (:keys)
              AND purpose = :purpose
              AND uploaded_by = CAST(:uploadedBy AS uuid)
              AND status = 'PENDING'
              AND expires_at > now()
              AND tenant_id = :tenantId
            RETURNING id""")
    List<UUID> claim(UUID tenantId, Collection<String> keys, String purpose, UUID uploadedBy);

    /** Attached files to RELEASED; a PENDING or RELEASED one is left alone. */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET status = 'RELEASED',
                updated_at = now(),
                updated_by = CAST(:updatedBy AS uuid),
                version = version + 1
            WHERE id IN (:ids)
              AND status IN ('IN_REVIEW', 'UPLOADED', 'MISSING')
              AND tenant_id = :tenantId""")
    int release(UUID tenantId, Collection<UUID> ids, UUID updatedBy);

    List<MediaFile> findByIdInOrderById(Collection<UUID> ids);

    long countByIdInAndStatus(Collection<UUID> ids, MediaFileStatus status);

    @Query("""
            select new com.example.app.media.dto.internal.MediaFileKey(file.id, file.key)
              from MediaFile file
             where file.id in :ids
             order by file.id""")
    List<MediaFileKey> findKeys(Collection<UUID> ids);

    /**
     * Leases a batch of attached files whose upload URL expired, oldest first
     * ({@code idx_media_files_in_review_upload_expires_at_id}). {@code SKIP LOCKED} passes over rows
     * another instance is leasing right now, and {@code lease_until} over the ones it leased before:
     * instances get disjoint batches, and a crashed instance's rows return when its lease runs out.
     * The lease is bookkeeping, not a change of the file: no version or audit columns.
     */
    @Query(nativeQuery = true, value = LEASE_FOR_VERIFICATION)
    List<UUID> leaseForVerification(UUID tenantId, int batchSize, long leaseSeconds);

    /** The verification's result, for files still IN_REVIEW (a removal since wins); ends the lease. */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET status = :status,
                lease_until = NULL,
                updated_at = now(),
                updated_by = NULL,
                version = version + 1
            WHERE id IN (:ids)
              AND status = 'IN_REVIEW'
              AND tenant_id = :tenantId""")
    int recordVerification(UUID tenantId, Collection<UUID> ids, String status);

    /** Like {@link #leaseForVerification}, for removed files whose upload URL expired ({@code idx_media_files_released_upload_expires_at_id}). */
    @Query(nativeQuery = true, value = LEASE_RELEASED)
    List<UUID> leaseReleased(UUID tenantId, int batchSize, long leaseSeconds);

    /** Like {@link #leaseForVerification}, for never-attached files past their claim window ({@code idx_media_files_pending_expires_at_id}). */
    @Query(nativeQuery = true, value = LEASE_EXPIRED_PENDING)
    List<UUID> leaseExpiredPending(UUID tenantId, int batchSize, long leaseSeconds);

    /**
     * Deletes the rows whose objects are gone. Only RELEASED and expired PENDING ones: neither can change
     * state any more (a claim needs an unexpired PENDING file), so nothing attached is ever deleted.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM media_files
            WHERE id IN (:ids)
              AND (status = 'RELEASED' OR (status = 'PENDING' AND expires_at < now()))
              AND tenant_id = :tenantId""")
    int deleteRemoved(UUID tenantId, Collection<UUID> ids);
}
