package com.example.app.metadata.api;

/**
 * Gapless business numbers (admission numbers, receipt numbers), one row per counter in
 * {@code counters}. Not configuration metadata: a counter changes on every use and is never JSON.
 *
 * <p>A counter name is UPPER_SNAKE_CASE, per context like metadata keys ({@code RECEIPT_NUMBER_7}),
 * declared as a constant or a factory next to the code that numbers things.</p>
 */
public interface Counters {

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
}
