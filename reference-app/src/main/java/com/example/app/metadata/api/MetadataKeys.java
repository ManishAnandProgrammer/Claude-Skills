package com.example.app.metadata.api;

import java.util.List;
import java.util.Optional;
// example:begin
import java.util.Objects;
import java.util.UUID;
// example:end

/**
 * Every configuration the application keeps. A key not declared here can't be read or written.
 *
 * <ul>
 *   <li>A <b>fixed</b> key is a constant: one value for the whole application (or tenant). The admin
 *       API ({@code /metadata/{key}}) reaches fixed keys only, through {@link #byName}.</li>
 *   <li>A <b>per-context</b> key is a factory method, its name a prefix plus the context's id in its
 *       canonical form ({@code OPENING_HOURS_<storeId>}). Only the module that owns the context
 *       writes it, through its own typed endpoint ({@code PUT /stores/{id}/opening-hours}),
 *       which checks the context's rules first; so {@link #byName} never resolves one.</li>
 * </ul>
 *
 * <p>An interface only because a module's {@code api} package holds contract types (ARCH-05);
 * nothing implements it.</p>
 */
public interface MetadataKeys {

    // example:begin
    /** The settings a new todo list starts from. */
    MetadataKey<TodoListSettings> TODO_LIST_DEFAULTS = new MetadataKey<>("TODO_LIST_DEFAULTS", TodoListSettings.class);

    String TODO_LIST_SETTINGS_PREFIX = "TODO_LIST_SETTINGS_";

    /** One todo list's settings, created with the list. */
    static MetadataKey<TodoListSettings> todoListSettings(UUID listId) {
        return new MetadataKey<>(TODO_LIST_SETTINGS_PREFIX + Objects.requireNonNull(listId, "listId"), TodoListSettings.class);
    }
    // example:end

    /** The fixed key of that name, if any. */
    static Optional<MetadataKey<?>> byName(String name) {
        List<MetadataKey<?>> fixedKeys = List.of(
                // Per-context keys are never listed: the context's owner writes them through its own endpoint.
                // example:begin
                TODO_LIST_DEFAULTS
                // example:end
        );
        for (MetadataKey<?> key : fixedKeys) {
            if (key.name().equals(name)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }
}
