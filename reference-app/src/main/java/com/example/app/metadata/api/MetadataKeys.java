package com.example.app.metadata.api;

import java.util.List;
import java.util.Optional;
// example:begin
import java.util.Objects;
import java.util.UUID;
// example:end

public interface MetadataKeys {

    // example:begin
    MetadataKey<TodoListSettings> TODO_LIST_DEFAULTS = new MetadataKey<>("TODO_LIST_DEFAULTS", TodoListSettings.class);

    String TODO_LIST_SETTINGS_PREFIX = "TODO_LIST_SETTINGS_";

    static MetadataKey<TodoListSettings> todoListSettings(UUID listId) {
        return new MetadataKey<>(TODO_LIST_SETTINGS_PREFIX + Objects.requireNonNull(listId, "listId"), TodoListSettings.class);
    }
    // example:end

    static Optional<MetadataKey<?>> fixedKeyNamed(String name) {
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
