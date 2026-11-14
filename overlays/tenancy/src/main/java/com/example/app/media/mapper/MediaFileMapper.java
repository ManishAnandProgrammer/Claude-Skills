package com.example.app.media.mapper;

import com.example.app.media.api.MediaFileView;
import com.example.app.common.tenancy.TenantContext;
import com.example.app.media.api.UploadPurpose;
import com.example.app.media.domain.MediaFile;
import com.example.app.media.dto.internal.PresignedUpload;
import com.example.app.media.dto.request.UploadFileRequest;
import com.example.app.media.dto.response.UploadUrlResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class MediaFileMapper {

    /** {@code PURPOSE/uuid}: no personal data in a key (keys appear in logs, URLs and audit trails), nothing to escape. */
    /** {tenantId}/{purpose}/{uuid}: one tenant's files are one prefix, to export or delete. */
    public String newKey(UploadPurpose purpose) {
        return TenantContext.id() + "/" + purpose.name() + "/" + UUID.randomUUID();
    }

    /** The upload URL's own expiry, so the jobs compare against exactly when the store stops accepting the PUT. */
    public MediaFile toEntity(String key, UploadPurpose purpose, UploadFileRequest file, UUID uploadedBy,
                              PresignedUpload upload, Duration pendingLifetime) {
        Instant uploadExpiresAt = upload.expiresAt();
        return MediaFile.builder()
                .key(key)
                .purpose(purpose)
                .fileName(file.fileName())
                .contentType(file.contentType())
                .sizeBytes(file.sizeBytes())
                .uploadedBy(uploadedBy)
                .uploadExpiresAt(uploadExpiresAt)
                .expiresAt(uploadExpiresAt.plus(pendingLifetime))
                .build();
    }

    public UploadUrlResponse toUploadUrlResponse(String key, PresignedUpload upload) {
        return new UploadUrlResponse(key, upload.url(), upload.headers(), upload.expiresAt());
    }

    public MediaFileView toView(MediaFile file, String url) {
        return new MediaFileView(file.getId(), file.getFileName(), file.getContentType(), file.getSizeBytes(), file.getStatus(), url);
    }
}
