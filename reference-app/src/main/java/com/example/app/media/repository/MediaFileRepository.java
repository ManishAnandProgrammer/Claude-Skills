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
 */
public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {

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
            RETURNING id""")
    List<UUID> claim(Collection<String> keys, String purpose, UUID uploadedBy);

    /** Attached files to RELEASED; a PENDING or RELEASED one is left alone. */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET status = 'RELEASED',
                updated_at = now(),
                updated_by = CAST(:updatedBy AS uuid),
                version = version + 1
            WHERE id IN (:ids)
              AND status IN ('IN_REVIEW', 'UPLOADED', 'MISSING')""")
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
     * Leases a batch of attached files whose upload URL expired, oldest first
     * ({@code idx_media_files_in_review_upload_expires_at_id}). {@code SKIP LOCKED} passes over rows
     * another instance is leasing right now, and {@code lease_until} over the ones it leased before:
     * instances get disjoint batches, and a crashed instance's rows return when its lease runs out.
     * The lease is bookkeeping, not a change of the file: no version or audit columns.
     */
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET lease_until = now() + :leaseSeconds * interval '1 second'
            WHERE id IN (SELECT id
                           FROM media_files
                          WHERE status = 'IN_REVIEW'
                            AND upload_expires_at < now()
                            AND (lease_until IS NULL OR lease_until < now())
                          ORDER BY upload_expires_at, id
                          LIMIT :batchSize
                          FOR UPDATE SKIP LOCKED)
            RETURNING id""")
    List<UUID> leaseForVerification(int batchSize, long leaseSeconds);

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
              AND status = 'IN_REVIEW'""")
    int recordVerification(Collection<UUID> ids, String status);

    /** Like {@link #leaseForVerification}, for removed files whose upload URL expired ({@code idx_media_files_released_upload_expires_at_id}). */
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET lease_until = now() + :leaseSeconds * interval '1 second'
            WHERE id IN (SELECT id
                           FROM media_files
                          WHERE status = 'RELEASED'
                            AND upload_expires_at < now()
                            AND (lease_until IS NULL OR lease_until < now())
                          ORDER BY upload_expires_at, id
                          LIMIT :batchSize
                          FOR UPDATE SKIP LOCKED)
            RETURNING id""")
    List<UUID> leaseReleased(int batchSize, long leaseSeconds);

    /** Like {@link #leaseForVerification}, for never-attached files past their claim window ({@code idx_media_files_pending_expires_at_id}). */
    @Query(nativeQuery = true, value = """
            UPDATE media_files
            SET lease_until = now() + :leaseSeconds * interval '1 second'
            WHERE id IN (SELECT id
                           FROM media_files
                          WHERE status = 'PENDING'
                            AND expires_at < now()
                            AND (lease_until IS NULL OR lease_until < now())
                          ORDER BY expires_at, id
                          LIMIT :batchSize
                          FOR UPDATE SKIP LOCKED)
            RETURNING id""")
    List<UUID> leaseExpiredPending(int batchSize, long leaseSeconds);

    /**
     * Deletes the rows whose objects are gone. Only RELEASED and expired PENDING ones: neither can change
     * state any more (a claim needs an unexpired PENDING file), so nothing attached is ever deleted.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM media_files
            WHERE id IN (:ids)
              AND (status = 'RELEASED' OR (status = 'PENDING' AND expires_at < now()))""")
    int deleteRemoved(Collection<UUID> ids);
}
