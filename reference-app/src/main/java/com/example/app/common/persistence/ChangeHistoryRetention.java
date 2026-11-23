package com.example.app.common.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

// tenancy:begin
// /** Run by a job, so outside any tenant: the platform's table resolves. */
// tenancy:end
@Component
@RequiredArgsConstructor
class ChangeHistoryRetention {

    /*
     * An ARRAY(...) of the locked ids, which PostgreSQL computes once (an init plan); as "id IN (SELECT ... LIMIT
     * ...)" the planner may run the subquery again per outer row and delete more than one batch in one transaction.
     * SKIP LOCKED: instances purging at once take disjoint rows.
     */
    static final String DELETE_BATCH = """
            DELETE FROM audit_log
            WHERE id = ANY (ARRAY(SELECT id
                                    FROM audit_log
                                   WHERE changed_at < :changedBefore
                                   LIMIT :batchSize
                                   FOR UPDATE SKIP LOCKED))""";

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public int deleteChangedBefore(Instant changedBefore, int batchSize) {
        return jdbc.update(DELETE_BATCH, new MapSqlParameterSource("changedBefore", Timestamp.from(changedBefore))
                .addValue("batchSize", batchSize));
    }
}
