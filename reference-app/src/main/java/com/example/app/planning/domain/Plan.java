package com.example.app.planning.domain;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
// tenancy-row:begin
// import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Days set aside for one todo, both ends inclusive. The todo is another module's, so it is an id here
 * (its foreign key is the database's, changeset 0011). Its days move only through {@link #reschedule}, both
 * ends at once; its todo and owner never change.
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

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanStatus status = PlanStatus.PLANNED;

    /** The day the user says it was done: set with DONE, never otherwise. */
    @Column(name = "done_on")
    private LocalDate doneOn;

    /**
     * Both ends in one call, so the range is never half moved, and only while PLANNED: a DONE or CANCELLED
     * plan is frozen ({@link #requirePlanned}). The request already refused an inverted
     * range (400); one reaching here is a caller's bug. The days' overlap is the database's to refuse
     * ({@code ex_plans_owner_id_dates}, at the flush).
     */
    public void reschedule(LocalDate newStartsOn, LocalDate newEndsOn) {
        requirePlanned();
        if (newEndsOn.isBefore(newStartsOn)) {
            throw new IllegalStateException("A plan can't end on %s, before it starts on %s".formatted(newEndsOn, newStartsOn));
        }
        startsOn = newStartsOn;
        endsOn = newEndsOn;
    }

    /**
     * A DONE or CANCELLED plan is final: every edit is refused with 409 naming the status ({@code PLAN_DONE},
     * {@code PLAN_CANCELLED}), before any other rule the edit checks. A delete stays open. Public because the
     * reschedule checks its todo's state before calling {@link #reschedule}: the service calls this first.
     */
    public void requirePlanned() {
        if (status == PlanStatus.DONE) {
            throw AppException.of(ErrorCode.PLAN_DONE, getId());
        }
        if (status == PlanStatus.CANCELLED) {
            throw AppException.of(ErrorCode.PLAN_CANCELLED, getId());
        }
    }

    /**
     * A move the lifecycle allows ({@link PlanStatus#canBecome}), else 409 {@code PLAN_STATUS_CHANGE_INVALID}
     * naming both statuses; a move to the current status is one it doesn't list. DONE records the day it was
     * done, which can't be before the plan's first: 400 {@code PLAN_DONE_ON_BEFORE_STARTS_ON}. The request
     * gives the day exactly when the target is DONE.
     */
    public void changeStatus(PlanStatus target, LocalDate newDoneOn) {
        if (!status.canBecome(target)) {
            throw AppException.of(ErrorCode.PLAN_STATUS_CHANGE_INVALID, status, target);
        }
        if (target == PlanStatus.DONE && newDoneOn.isBefore(startsOn)) {
            throw AppException.of(ErrorCode.PLAN_DONE_ON_BEFORE_STARTS_ON, newDoneOn, startsOn);
        }
        status = target;
        doneOn = target == PlanStatus.DONE ? newDoneOn : null;
    }
}
