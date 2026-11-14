package com.example.app.todo.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * One tag on one todo, a member of the todo's aggregate: written only through {@link Todo#replaceTags}.
 * An entity rather than a {@code @ManyToMany} join table, so the row can carry its owner, which the
 * composite foreign keys tie to the todo's and the tag's (changeset 0006). Like every entity it has a
 * UUIDv7 id, the audit columns (who put the tag on, and when) and a version; a row is inserted and
 * deleted, never updated.
 */
@Entity
@Table(name = "todo_tags")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TodoTag extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "todo_id", nullable = false, updatable = false)
    private Todo todo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false, updatable = false)
    private Tag tag;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
}
