package com.example.app.media.dto.response;

import com.example.app.media.api.UploadPurpose;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "One purpose's upload limits")
public record UploadPolicyResponse(
        @Schema(
                // example:begin
                example = "TODO_ATTACHMENT",
                // example:end
                description = "The purpose")
        UploadPurpose purpose,
        @Schema(description = "Smallest file accepted, in bytes", example = "1") long minSizeBytes,
        @Schema(description = "Largest file accepted, in bytes", example = "10485760") long maxSizeBytes,
        @Schema(description = "Fewest files a record holds", example = "0") int minFiles,
        @Schema(description = "Most files a record holds, and per upload request", example = "5") int maxFiles,
        @Schema(description = "Exact content types accepted") List<String> contentTypes,
        @Schema(description = "Whether you may upload for this purpose (a hint for the UI; POST /upload-urls decides)") boolean canUpload,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it; null when seeded") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Send it back when updating", example = "0") Long version) {
}
