package com.example.app.metadata.api;

import java.util.Objects;
import java.util.UUID;

public interface Counters {

    /** The id in its canonical (lower-case) form, as {@code ck_counters_key} accepts. */
    static String perContext(String prefix, UUID contextId) {
        return Objects.requireNonNull(prefix, "prefix") + "_" + Objects.requireNonNull(contextId, "contextId");
    }

    void create(String name, long lastValue);

    void createIfAbsent(String name, long lastValue);

    /**
     * Holds the counter's row until the caller's transaction (which it requires) ends: take the number
     * last, after every check of the create. Numbers stay gapless only while their records are never deleted.
     */
    long next(String name);

    void delete(String name);
}
