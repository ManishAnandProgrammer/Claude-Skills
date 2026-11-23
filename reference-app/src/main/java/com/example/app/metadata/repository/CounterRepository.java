package com.example.app.metadata.repository;

import com.example.app.metadata.domain.Counter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CounterRepository extends JpaRepository<Counter, UUID> {

    /**
     * The row lock it takes lasts until the caller's transaction ends: the next caller waits for that
     * commit (or rollback) and so reads a number no one else holds. Sets the audit columns and the version
     * itself: a native update bypasses auditing.
     */
    @Query(nativeQuery = true, value = """
            UPDATE counters
            SET last_value = last_value + 1,
                updated_at = :updatedAt,
                updated_by = CAST(:updatedBy AS uuid),
                version = version + 1
            WHERE key = :key
            RETURNING last_value""")
    Optional<Long> increment(String key, Instant updatedAt, UUID updatedBy);

    /**
     * A concurrent seed (or a second call) finds the key taken and inserts nothing, without an error that
     * would doom the caller's transaction.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO counters (id, key, last_value, created_at, updated_at, created_by, updated_by, version)
            VALUES (uuidv7(), :key, :lastValue, :createdAt, :createdAt, CAST(:createdBy AS uuid),
                    CAST(:createdBy AS uuid), 0)
            ON CONFLICT ON CONSTRAINT uq_counters_key DO NOTHING""")
    int insertIfAbsent(String key, long lastValue, Instant createdAt, UUID createdBy);

    void deleteByKey(String key);
}
