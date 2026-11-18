package com.example.app.media.dto.request;

import com.example.app.common.limits.ConfiguredMax;
import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.validation.CrossField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

@Schema(description = "A purpose's new limits, as a whole")
public record UploadPolicyUpdateRequest(
        @Schema(description = "Smallest file accepted, in bytes", example = "1")
        @NotNull @Positive
        Long minSizeBytes,

        @Schema(description = "Largest file accepted, in bytes, up to a configured maximum", example = "10485760")
        @NotNull @Positive @ConfiguredMax("upload-policy.max-size-bytes")
        Long maxSizeBytes,

        @Schema(description = "Fewest files a record of this purpose holds", example = "0")
        @NotNull @PositiveOrZero
        Integer minFiles,

        @Schema(description = "Most files a record of this purpose holds, and per upload request, up to a configured maximum", example = "5")
        @NotNull @Positive @ConfiguredMax("upload-policy.max-files")
        Integer maxFiles,

        @Schema(description = """
                Exact content types accepted, each one the application allows (never SVG, HTML or XML), up \
                to a configured number""", example = "[\"image/jpeg\", \"application/pdf\"]")
        @NotEmpty @ConfiguredMaxSize("upload-policy.max-content-types")
        Set<@NotBlank @MaxLength("media.content-type") String> contentTypes,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {

    // Jackson builds it before validation: a null, or a null element, reaches the constraints that name it.
    public UploadPolicyUpdateRequest {
        contentTypes = Objects.isNull(contentTypes) ? null : Collections.unmodifiableSet(contentTypes);
    }

    @CrossField(reportedOn = "maxSizeBytes", message = "{upload-policy.size-range}")
    @Schema(hidden = true)
    public boolean isSizeRangeOrdered() {
        return Objects.isNull(minSizeBytes) || Objects.isNull(maxSizeBytes) || minSizeBytes <= maxSizeBytes;
    }

    @CrossField(reportedOn = "maxFiles", message = "{upload-policy.file-range}")
    @Schema(hidden = true)
    public boolean isFileRangeOrdered() {
        return Objects.isNull(minFiles) || Objects.isNull(maxFiles) || minFiles <= maxFiles;
    }
}
