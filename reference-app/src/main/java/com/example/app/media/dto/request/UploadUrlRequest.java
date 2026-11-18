package com.example.app.media.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.media.api.UploadPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Schema(description = "Files to upload for one purpose")
public record UploadUrlRequest(
        @Schema(
                // example:begin
                example = "TODO_ATTACHMENT",
                // example:end
                description = "What the files are for (GET /upload-policies lists the purposes and their limits)")
        @NotNull
        UploadPurpose purpose,

        @Schema(description = "The files, at most the purpose's maxFiles")
        @NotEmpty @ConfiguredMaxSize("upload-policy.max-files")
        List<@NotNull @Valid UploadFileRequest> files) {

    // Jackson builds it before validation: a null, or a null element, reaches the constraints that name it.
    public UploadUrlRequest {
        files = Objects.isNull(files) ? null : Collections.unmodifiableList(files);
    }
}
