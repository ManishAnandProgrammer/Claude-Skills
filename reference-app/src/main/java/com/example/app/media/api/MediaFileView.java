package com.example.app.media.api;

import java.util.UUID;

/**
 * @param url null while MISSING
 */
public record MediaFileView(UUID id, String fileName, String contentType, long sizeBytes, MediaFileStatus status, String url) {
}
