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
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "time_entries")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
// public class TimeEntry extends TenantOwnedEntity {
// tenancy-row:else
public class TimeEntry extends AuditableEntity {
// tenancy-row:end

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "todo_id", nullable = false, updatable = false)
    private Todo todo;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Setter
    @Column(nullable = false)
    private Integer minutes;

    @Setter
    @Column(name = "spent_on", nullable = false)
    private LocalDate spentOn;

    @Setter
    private String note;

    @Setter
    @Column(nullable = false)
    private boolean billable;
}
