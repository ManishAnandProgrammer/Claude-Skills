package com.example.app.media.dto.internal;

import java.time.Instant;
import java.util.Map;

/** A signed PUT: the URL, the headers the client must send exactly, and when it expires. */
public record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {
}
