package com.example.app.media.domain;

// tenancy-row:begin
// import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
import com.example.app.media.api.UploadPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/** One purpose's limits. Seeded by a changeset per purpose; changed by an admin ({@link #replaceLimits}). */
@Entity
@Table(name = "upload_policies")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
// public class UploadPolicy extends TenantOwnedEntity {
// tenancy-row:else
public class UploadPolicy extends AuditableEntity {
// tenancy-row:end

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private UploadPurpose purpose;

    @Column(name = "min_size_bytes", nullable = false)
    private long minSizeBytes;

    @Column(name = "max_size_bytes", nullable = false)
    private long maxSizeBytes;

    @Column(name = "min_files", nullable = false)
    private int minFiles;

    @Column(name = "max_files", nullable = false)
    private int maxFiles;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "content_types", nullable = false, columnDefinition = "text[]")
    private List<String> contentTypes;

    /** The ranges are checked by the request and by the table's constraints. */
    public void replaceLimits(long minSizeBytes, long maxSizeBytes, int minFiles, int maxFiles, List<String> contentTypes) {
        this.minSizeBytes = minSizeBytes;
        this.maxSizeBytes = maxSizeBytes;
        this.minFiles = minFiles;
        this.maxFiles = maxFiles;
        this.contentTypes = new ArrayList<>(contentTypes);
    }

    public boolean allowsSize(long sizeBytes) {
        return sizeBytes >= minSizeBytes && sizeBytes <= maxSizeBytes;
    }

    public boolean allowsContentType(String contentType) {
        return contentTypes.contains(contentType);
    }
}
