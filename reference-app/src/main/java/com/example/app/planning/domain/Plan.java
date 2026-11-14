package com.example.app.planning.domain;

// tenancy-row:begin
// import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Days set aside for one todo, both ends inclusive. The todo is another module's, so it is an id here
 * (its foreign key is the database's, changeset 0011). A plan never changes: to move it, delete it and
 * create another.
 */
@Entity
@Table(name = "plans")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
// public class Plan extends TenantOwnedEntity {
// tenancy-row:else
public class Plan extends AuditableEntity {
// tenancy-row:end

    @Column(name = "todo_id", nullable = false, updatable = false)
    private UUID todoId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "starts_on", nullable = false, updatable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false, updatable = false)
    private LocalDate endsOn;
}
