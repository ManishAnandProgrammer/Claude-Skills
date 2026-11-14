package com.example.app.media.dto.request;

import com.example.app.media.api.UploadPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

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
        @NotEmpty
        List<@NotNull @Valid UploadFileRequest> files) {
}
