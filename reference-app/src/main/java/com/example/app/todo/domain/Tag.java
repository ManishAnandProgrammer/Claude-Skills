package com.example.app.todo.domain;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(name = "tags")
@DropdownCandidate(label = "Tag", displayField = "name", activeField = "active", searchable = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag extends AuditableEntity {

    @Setter
    @Column(nullable = false)
    private String name;

    @Setter
    private String color;

    @Setter
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
}
