package com.example.app.todo.domain;

// tenancy-row:begin
// import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
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

@Entity
@Table(name = "todo_tags")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
// public class TodoTag extends TenantOwnedEntity {
// tenancy-row:else
public class TodoTag extends AuditableEntity {
// tenancy-row:end

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "todo_id", nullable = false, updatable = false)
    private Todo todo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false, updatable = false)
    private Tag tag;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
}
