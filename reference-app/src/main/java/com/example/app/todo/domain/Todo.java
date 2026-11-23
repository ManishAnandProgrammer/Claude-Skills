package com.example.app.todo.domain;

import com.example.app.common.dropdown.DropdownCandidate;
// tenancy-row:begin
// import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
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
// media:begin
import java.util.Collection;
// media:end
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "todos")
@DropdownCandidate(label = "Todo", displayField = "title", mandatoryFilters = "todoList.id", searchable = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
// public class Todo extends TenantOwnedEntity {
// tenancy-row:else
public class Todo extends AuditableEntity {
// tenancy-row:end

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "todo_list_id", nullable = false)
    private TodoList todoList;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
    // metadata:begin

    @Column(updatable = false)
    private Long number;
    // metadata:end

    @Setter
    @Column(name = "requester_id")
    private UUID requesterId;

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

    @Column(name = "due_sort", insertable = false, updatable = false)
    private LocalDate dueSort;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** A change bumps the version, which Hibernate doesn't do for a mapped-by collection by default. */
    @Getter(AccessLevel.NONE)
    @Builder.Default
    @OptimisticLock(excluded = false)
    @OneToMany(mappedBy = "todo", cascade = {CascadeType.PERSIST, CascadeType.REMOVE}, orphanRemoval = true)
    private Set<TodoTag> tagLinks = new HashSet<>();
    // media:begin

    /** Like the tags, a change bumps the version: two concurrent changes of its files can't both pass the count check. */
    @Getter(AccessLevel.NONE)
    @Builder.Default
    @OptimisticLock(excluded = false)
    @OneToMany(mappedBy = "todo", cascade = {CascadeType.PERSIST, CascadeType.REMOVE}, orphanRemoval = true)
    private Set<TodoAttachment> attachmentLinks = new HashSet<>();
    // media:end

    // metadata:begin
    public void assignNumber(long next) {
        if (Objects.nonNull(number)) {
            throw new IllegalStateException("Todo %s is numbered already".formatted(getId()));
        }
        number = next;
    }

    // metadata:end
    public void moveTo(TodoList target) {
        if (!target.getOwnerId().equals(ownerId)) {
            throw new IllegalStateException("Todo %s can't move to list %s of another owner".formatted(getId(), target.getId()));
        }
        todoList = target;
    }

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

    // media:begin
    public void attach(Collection<UUID> mediaFileIds) {
        for (UUID mediaFileId : mediaFileIds) {
            attachmentLinks.add(TodoAttachment.builder().todo(this).mediaFileId(mediaFileId).build());
        }
    }

    public void detach(Collection<UUID> mediaFileIds) {
        attachmentLinks.removeIf(link -> mediaFileIds.contains(link.getMediaFileId()));
    }

    // media:end
    public void changeStatus(TodoStatus target, Instant now) {
        if (status == target) {
            return;
        }
        status = target;
        completedAt = target == TodoStatus.DONE ? now : null;
    }
}
