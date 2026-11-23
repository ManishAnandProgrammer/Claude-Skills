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

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@SuperBuilder
@NoArgsConstructor
public abstract class AuditableEntity {

    /**
     * {@code style = VERSION_7} uses Hibernate's one shared, monotonic generator;
     * {@code algorithm = UuidVersion7Strategy.class} would build one per entity through a constructor Hibernate
     * marks internal.
     */
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

    /** A {@code null} version also tells Spring Data the entity is new, so {@code save} persists without a select. */
    @Version
    @Column(nullable = false)
    private Long version;

    public void checkVersion(Long expected) {
        if (!Objects.equals(version, expected)) {
            throw new ObjectOptimisticLockingFailureException(getClass(), id);
        }
    }
}
