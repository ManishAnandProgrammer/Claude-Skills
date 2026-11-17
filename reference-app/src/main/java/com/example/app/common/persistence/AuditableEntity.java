package com.example.app.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Base of every entity: a time-ordered id, the audit columns and the optimistic-locking version.
 *
 * <ul>
 *   <li>{@code id}: UUIDv7, assigned by Hibernate in the application (not a database default), so
 *       inserts batch and need no read-back. {@code style = VERSION_7} uses Hibernate's one shared,
 *       monotonic generator; {@code algorithm = UuidVersion7Strategy.class} would build one per entity
 *       through a constructor Hibernate marks internal;</li>
 *   <li>{@code createdAt} / {@code updatedAt} / {@code createdBy} / {@code updatedBy}: filled by Spring
 *       Data auditing ({@code JpaAuditingConfig}); {@code createdBy} / {@code updatedBy} are the
 *       signed-in user's id, {@code null} outside a request (signup, scheduled jobs);</li>
 *   <li>{@code version}: every update checks and increments it, so of two transactions that loaded
 *       the same row the second to commit fails (409 {@code OPTIMISTIC_LOCK_CONFLICT}). A {@code null}
 *       version also tells Spring Data the entity is new, so {@code save} persists without a
 *       select.</li>
 * </ul>
 *
 * <p>No setters: auditing and Hibernate write these fields.</p>
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@SuperBuilder
@NoArgsConstructor
public abstract class AuditableEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(nullable = false)
    private Long version;

    /**
     * Refuses a change made from a stale copy: the version the client last read must be the current
     * one. It fails like a write that lost the race at the flush, so both answer 409
     * {@code OPTIMISTIC_LOCK_CONFLICT} through one handler.
     */
    public void checkVersion(Long expected) {
        if (!Objects.equals(version, expected)) {
            throw new ObjectOptimisticLockingFailureException(getClass(), id);
        }
    }
}
