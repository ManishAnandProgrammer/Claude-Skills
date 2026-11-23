package com.example.app.media.dto.internal;

import java.time.Instant;
import java.util.Map;

public record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {

    public PresignedUpload {
        headers = Map.copyOf(headers);
    }
}
