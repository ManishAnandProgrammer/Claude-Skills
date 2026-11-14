package com.example.app.metadata.api;

/**
 * Configuration metadata for other modules: one typed value per declared key, read often and
 * changed rarely (by an admin, through {@code PUT /api/v1/metadata/{key}}).
 *
 * <p>Seeding is the caller's lifecycle code: whatever creates a context creates its configuration
 * in the same transaction, e.g. "session created" copies the previous session's with
 * {@link #createFrom}.</p>
 */
public interface Metadata {

    /** The stored value; 404 {@code METADATA_NOT_FOUND} when it was never created. */
    <T> T get(MetadataKey<T> key);

    /** Creates the key's value; 400 {@code VALIDATION_FAILED} when the record breaks its constraints. */
    <T> void create(MetadataKey<T> key, T value);

    /**
     * Seeds {@code target} with {@code source}'s current value: a new context starting from the
     * previous one (or from a fixed key of defaults). 404 {@code METADATA_NOT_FOUND} when the source
     * was never created.
     */
    <T> T createFrom(MetadataKey<T> source, MetadataKey<T> target);

    /** Replaces the value when {@code version} is the current one; 409 {@code OPTIMISTIC_LOCK_CONFLICT} otherwise. */
    <T> T update(MetadataKey<T> key, T value, long version);
}
