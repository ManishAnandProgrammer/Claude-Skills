package com.example.app.media.service;

import java.util.Objects;
import java.util.Set;

/**
 * Compared exactly: a substring or family match ({@code image/*}) lets {@code image/svg+xml} through,
 * which a browser runs as a document. SVG, HTML and XML are never here. Code, not configuration:
 * widening it is a security decision.
 */
public final class AllowedContentTypes {

    private static final Set<String> ALLOWED = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/heic",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private AllowedContentTypes() {
    }

    public static boolean isAllowed(String contentType) {
        return Objects.nonNull(contentType) && ALLOWED.contains(contentType);
    }
}
