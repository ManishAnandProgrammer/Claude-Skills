package com.example.app.common.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * The one statement the application runs on {@code audit_log} (changeset 0035): deleting rows past their
 * retention.
 *   <!-- tenancy:begin -->
 <!-- * A job's, so it runs outside any tenant: the platform's table resolves. -->
 *   <!-- tenancy:end --> The database's triggers write the rows; nothing in the application reads them (compliance only,
 * house-review DATA-08), and no entity maps the table.
 */
@Component
@RequiredArgsConstructor
class ChangeHistoryRetention {

    /*
     * One purge batch. The batch is an ARRAY(...) of the locked ids, which PostgreSQL computes once (an init plan);
     * as "id IN (SELECT ... LIMIT ...)" the planner may run the subquery again per outer row and delete more than one
     * batch in one transaction (house-review DATA-29). SKIP LOCKED: instances purging at once take disjoint rows.
     */
    static final String DELETE_BATCH = """
            DELETE FROM audit_log
            WHERE id = ANY (ARRAY(SELECT id
                                    FROM audit_log
                                   WHERE changed_at < ?
                                   LIMIT ?
                                   FOR UPDATE SKIP LOCKED))""";

    private final JdbcTemplate jdbcTemplate;

    /** Deletes up to {@code batchSize} rows changed before {@code changedBefore}; the rows deleted. */
    @Transactional
    public int deleteChangedBefore(Instant changedBefore, int batchSize) {
        return jdbcTemplate.update(DELETE_BATCH, Timestamp.from(changedBefore), batchSize);
    }
}
