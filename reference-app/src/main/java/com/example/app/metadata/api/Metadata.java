package com.example.app.metadata.api;

/**
 * Configuration metadata for other modules: one typed value per declared key, read often and
 * changed rarely.
 *
 * <p>Who writes what: a fixed key's value is replaced by an admin through
 * {@code PUT /api/v1/metadata/{key}}. A per-context value belongs to the module that owns the
 * context: its lifecycle code seeds it in the same transaction that creates the context (e.g.
 * "store opened" copies the defaults with {@link #createFrom}), its own typed endpoint
 * checks the context's rules and then calls {@link #update}, and its delete calls {@link #delete}.</p>
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

    /**
     * Replaces the value when {@code version} is the current one; 409 {@code OPTIMISTIC_LOCK_CONFLICT}
     * otherwise. A context's own rules ("a closed store's opening hours can't change") are checked by its
     * owner before this call.
     */
    <T> T update(MetadataKey<T> key, T value, long version);

    /**
     * Removes the value, in the caller's transaction, so a context's delete takes its configuration
     * with it. None stored: nothing happens.
     */
    void delete(MetadataKey<?> key);
}
