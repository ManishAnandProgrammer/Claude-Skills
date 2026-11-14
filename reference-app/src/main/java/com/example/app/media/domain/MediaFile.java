package com.example.app.media.domain;

// tenancy-row:begin
// import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
import com.example.app.media.api.MediaFileStatus;
import com.example.app.media.api.UploadPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * One file someone was given an upload URL for. Mapped for its creation and for reads: every status
 * change is one conditional UPDATE in {@code MediaFileRepository}, so two writers (a claim, a job)
 * can't both act on the same state.
 */
@Entity
@Table(name = "media_files")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
// public class MediaFile extends TenantOwnedEntity {
// tenancy-row:else
public class MediaFile extends AuditableEntity {
// tenancy-row:end

    /** {@code PURPOSE/uuid}: the object's key in the bucket, and the upload key clients send back. */
    @Column(nullable = false, updatable = false)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private UploadPurpose purpose;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaFileStatus status = MediaFileStatus.PENDING;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    /** When the signed PUT stops being accepted. */
    @Column(name = "upload_expires_at", nullable = false, updatable = false)
    private Instant uploadExpiresAt;

    /** When a file that was never attached stops being claimable. */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;
}
