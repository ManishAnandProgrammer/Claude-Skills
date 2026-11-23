package com.example.app.media.api;

public enum MediaFileStatus {
    UNCLAIMED,
    ATTACHED,
    VERIFIED,
    /** Final; it keeps its slot on the record. */
    MISSING,
    RELEASED
}
