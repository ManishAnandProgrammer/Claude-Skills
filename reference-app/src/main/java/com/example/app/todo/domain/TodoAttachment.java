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
 * One file on one todo, a member of the todo's aggregate: written only through {@link Todo#attach} and
 * {@link Todo#detach}. The file itself is the media module's, so it is an id here (its foreign key is
 * the database's, changeset 0010); a row is inserted and deleted, never updated.
 */
@Entity
@Table(name = "todo_attachments")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TodoAttachment extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "todo_id", nullable = false, updatable = false)
    private Todo todo;

    @Column(name = "media_file_id", nullable = false, updatable = false)
    private UUID mediaFileId;
}
