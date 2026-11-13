package com.example.app.todo.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Its status changes only through {@link #changeStatus}, which keeps {@code completedAt} in step.
 * Its list belongs to the same owner: {@link #moveTo} checks it, and so does the composite foreign
 * key.
 */
@Entity
@Table(name = "todos")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Todo extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "todo_list_id", nullable = false)
    private TodoList todoList;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Setter
    @Column(nullable = false)
    private String title;

    @Setter
    private String notes;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TodoStatus status = TodoStatus.OPEN;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TodoPriority priority;

    @Setter
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Written by the database (a generated column): the due date, or 9999-12-31 when there is none. */
    @Column(name = "due_sort", insertable = false, updatable = false)
    private LocalDate dueSort;

    @Column(name = "completed_at")
    private Instant completedAt;

    public void moveTo(TodoList target) {
        if (!target.getOwnerId().equals(ownerId)) {
            throw new IllegalStateException("Todo %s can't move to list %s of another owner".formatted(getId(), target.getId()));
        }
        todoList = target;
    }

    /** Asking for the status it already has changes nothing, so the request is idempotent. */
    public void changeStatus(TodoStatus target, Instant now) {
        if (status == target) {
            return;
        }
        status = target;
        completedAt = target == TodoStatus.DONE ? now : null;
    }
}
