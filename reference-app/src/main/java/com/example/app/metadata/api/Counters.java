package com.example.app.metadata.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Gapless business numbers (invoice numbers, order numbers), one row per counter in
 * {@code counters}. Not configuration metadata: a counter changes on every use and is never JSON.
 *
 * <p>A counter name is UPPER_SNAKE_CASE; a per-context one is {@link #perContext} (the same form as a
 * per-context metadata key). The module that numbers with a counter declares its name (a constant,
 * or a prefix for {@link #perContext}). When the module that creates and deletes the context can't
 * depend on that module (the store module seeds the invoice counter that the billing module
 * numbers with, and billing depends on stores), the context's owner declares the name instead, and
 * the numbering module reads it from there.</p>
 *
 * <p>A fixed counter (one for the whole application) is seeded by the changeset that needs it; a
 * per-context one by the lifecycle code that creates its context.</p>
 *   <!-- tenancy:begin -->
 *
 * <p>With tenancy {@code counters} is tenant data, so a fixed counter is one per tenant: a tenant's
 * display numbers (ticket #1, #2, ...) use a fixed name, never {@link #perContext} with the tenant's id.
 * Schema per tenant seeds it in the tenant changeset (it runs in every new schema). Row mode seeds it from
 * a {@code TenantProvisioned} listener with {@link #createIfAbsent}, beside a changeset that backfills
 * the tenants already there: the backfill also reaches a tenant still PENDING, whose approval then finds
 * the row. The create calls {@link #createIfAbsent} before {@link #next} too, so a tenant that an
 * instance without the listener activated (a rolling deploy) still numbers from 1.</p>
 *   <!-- tenancy:end -->
 *
 * <p>{@link #next} holds the counter's row until the caller commits, so the creates numbered by one
 * counter queue one behind the other: take the number last, after every check of the create. Numbers
 * stay gapless only while their records are never deleted; a display number whose records may be
 * deleted accepts the gap (a deleted record's number is never handed out again), and says so in its
 * API text.</p>
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
     * Seeds a counter unless it exists (then nothing changes), in the caller's transaction and without an
     * error that would doom it: for a seed that may meet the row created another way first, and for a
     * create that must number even where its seed never ran.
     */
    void createIfAbsent(String name, long lastValue);

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
