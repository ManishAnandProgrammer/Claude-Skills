package com.example.app.metadata.api;

import java.util.Objects;
import java.util.regex.Pattern;

public record MetadataKey<T>(String name, Class<T> type) {

    static final String CANONICAL_UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** Also the database's {@code ck_configuration_metadata_key}. */
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]*(" + CANONICAL_UUID + ")?");

    public MetadataKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Metadata key %s is not UPPER_SNAKE_CASE (with an optional UUID)".formatted(name));
        }
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Metadata key %s: its value %s is not a record".formatted(name, type.getName()));
        }
    }
}
