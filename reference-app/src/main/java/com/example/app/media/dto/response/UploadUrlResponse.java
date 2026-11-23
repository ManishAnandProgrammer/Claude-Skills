package com.example.app.media.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "Where and how to upload one file")
public record UploadUrlResponse(
        @Schema(
                // example:begin
                // tenancy:begin
                // example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d/TODO_ATTACHMENT/0b6f1c1e-3c55-4c3a-9b1e-5f0c2d7a8e41",
                // tenancy:else
                example = "TODO_ATTACHMENT/0b6f1c1e-3c55-4c3a-9b1e-5f0c2d7a8e41",
                // tenancy:end
                // example:end
                description = "Send it in the owning record's create or update to attach the file")
        String uploadKey,
        @Schema(description = "PUT the file's bytes here, before expiresAt")
        String url,
        @Schema(description = "Send exactly these headers with the PUT (Content-Type, Content-Disposition): they are signed")
        Map<String, String> headers,
        @Schema(description = "When the URL stops accepting the upload")
        Instant expiresAt) {

    public UploadUrlResponse {
        headers = Map.copyOf(headers);
    }
}
