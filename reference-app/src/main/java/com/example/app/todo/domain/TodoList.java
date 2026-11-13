package com.example.app.todo.domain;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(name = "todo_lists")
@DropdownCandidate(label = "Todo list", displayField = "name", searchable = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TodoList extends AuditableEntity {

    @Setter
    @Column(nullable = false)
    private String name;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
}
