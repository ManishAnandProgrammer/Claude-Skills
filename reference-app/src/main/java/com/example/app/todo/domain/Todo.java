package com.example.app.todo.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OptimisticLock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Its status changes only through {@link #changeStatus}, which keeps {@code completedAt} in step.
 * Its list belongs to the same owner: {@link #moveTo} checks it, and so does the composite foreign
 * key. Its tags too: {@link #replaceTags} checks them, and so do the composite foreign keys of
 * {@code todo_tags}.
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

    /**
     * Changed only through {@link #replaceTags}. A change bumps the version, which a mapped-by
     * collection doesn't by default: which tags it carries is part of the todo a client edits. Read
     * through queries (TodoService), so no other load of a todo touches it.
     */
    @Getter(AccessLevel.NONE)
    @Builder.Default
    @OptimisticLock(excluded = false)
    @OneToMany(mappedBy = "todo", cascade = {CascadeType.PERSIST, CascadeType.REMOVE}, orphanRemoval = true)
    private Set<TodoTag> tagLinks = new HashSet<>();

    public void moveTo(TodoList target) {
        if (!target.getOwnerId().equals(ownerId)) {
            throw new IllegalStateException("Todo %s can't move to list %s of another owner".formatted(getId(), target.getId()));
        }
        todoList = target;
    }

    /**
     * Makes {@code replacement} its tags: the ones it already carries stay as they are, so carrying the
     * same tags again changes nothing. The service loads them by this todo's owner, so another owner's
     * tag is an impossible state.
     */
    public void replaceTags(List<Tag> replacement) {
        for (Tag tag : replacement) {
            if (!ownerId.equals(tag.getOwnerId())) {
                throw new IllegalStateException("Todo %s can't carry tag %s of another owner".formatted(getId(), tag.getId()));
            }
        }
        Set<UUID> wanted = replacement.stream().map(Tag::getId).collect(Collectors.toSet());
        tagLinks.removeIf(link -> !wanted.contains(link.getTag().getId()));
        Set<UUID> carried = tagIds();
        for (Tag tag : replacement) {
            if (!carried.contains(tag.getId())) {
                tagLinks.add(TodoTag.builder().todo(this).tag(tag).ownerId(ownerId).build());
            }
        }
    }

    Set<UUID> tagIds() {
        return tagLinks.stream().map(link -> link.getTag().getId()).collect(Collectors.toSet());
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
