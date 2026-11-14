package com.example.app.metadata.repository;

import com.example.app.metadata.domain.Counter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CounterRepository extends JpaRepository<Counter, UUID> {

    /**
     * Increments and returns the counter in one statement. The row lock it takes lasts until the
     * caller's transaction ends: the next caller waits for that commit (or rollback) and so reads a
     * number no one else holds. Sets the audit columns and the version itself: a native update
     * bypasses auditing. Empty when no counter has that key.
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

    void deleteByKey(String key);
}
