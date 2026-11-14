package com.example.app.media.dto.internal;

import java.util.UUID;

/** A file a job works on: its id for the database, its key for the store. */
public record MediaFileKey(UUID id, String key) {
}
