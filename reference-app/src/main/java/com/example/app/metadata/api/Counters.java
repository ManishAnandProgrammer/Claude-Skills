package com.example.app.metadata.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Gapless business numbers (admission numbers, receipt numbers), one row per counter in
 * {@code counters}. Not configuration metadata: a counter changes on every use and is never JSON.
 *
 * <p>A counter name is UPPER_SNAKE_CASE; a per-context one is {@link #perContext} (the same form as a
 * per-context metadata key). The module that numbers with a counter declares its name (a constant,
 * or a prefix for {@link #perContext}). When the module that creates and deletes the context can't
 * depend on that module (the session's module seeds the receipt counter that the fee module
 * numbers with, and fee depends on sessions), the context's owner declares the name instead, and the
 * numbering module reads it from there.</p>
 *
 * <p>A fixed counter (one for the whole application) is seeded by the changeset that needs it; a
 * per-context one by the lifecycle code that creates its context.</p>
 */
public interface Counters {

    /** {@code prefix + "_" + contextId}, the id in its canonical (lower-case) form, as {@code ck_counters_key} accepts. */
    static String perContext(String prefix, UUID contextId) {
        return Objects.requireNonNull(prefix, "prefix") + "_" + Objects.requireNonNull(contextId, "contextId");
    }

    /**
     * Seeds a counter; the first {@link #next} returns {@code lastValue + 1}. A counter that already
     * exists is refused at the flush: 409 {@code DUPLICATE_RECORD}.
     */
    void create(String name, long lastValue);

    /**
     * The next number, inside the caller's transaction, which it requires. The row stays locked
     * until that transaction ends, so concurrent callers queue one behind the other, and a rollback
     * hands the number back: no gaps, no duplicates. Keep that transaction short.
     */
    long next(String name);

    /**
     * Removes the counter, in the caller's transaction: the context's delete takes its counter with
     * it. None stored: nothing happens. Records numbered with it are never deleted (their numbers
     * would leave a gap), so delete a context only while nothing was numbered in it.
     */
    void delete(String name);
}
