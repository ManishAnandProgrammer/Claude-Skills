package com.example.app.media.dto.request;

import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "One file to upload, described exactly as the browser will send it")
public record UploadFileRequest(
        @Schema(description = "The file's name, shown when it is downloaded", example = "receipt.pdf")
        @NotBlank @MaxLength("media.file-name")
        String fileName,

        @Schema(description = "Its exact content type (File.type), one of the purpose's contentTypes", example = "application/pdf")
        @NotBlank @MaxLength("media.content-type")
        String contentType,

        @Schema(description = """
                Its exact size in bytes (File.size), within the purpose's minSizeBytes and maxSizeBytes. The \
                URL is signed for this size: the store refuses a body of any other size.""", example = "48213")
        @NotNull @Positive
        Long sizeBytes) {
}
