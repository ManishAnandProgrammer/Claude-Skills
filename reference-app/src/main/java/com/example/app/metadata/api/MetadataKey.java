package com.example.app.metadata.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One configuration's key: its stored name and the record its value is read into. Declared only in
 * {@link MetadataKeys}, so every key a caller or the admin API can reach is listed in one place.
 *
 * @param <T> the value's record
 */
public record MetadataKey<T>(String name, Class<T> type) {

    /** A context id in a key: the canonical (lower-case) form {@link java.util.UUID#toString()} writes. */
    static final String CANONICAL_UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** UPPER_SNAKE_CASE, optionally ending in a context id. Also the database's {@code ck_configuration_metadata_key}. */
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]*(" + CANONICAL_UUID + ")?");

    public MetadataKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Metadata key %s is not UPPER_SNAKE_CASE (with an optional UUID)".formatted(name));
        }
        // A record's components are the JSON contract, and its compact constructor holds the defaults.
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Metadata key %s: its value %s is not a record".formatted(name, type.getName()));
        }
    }
}
