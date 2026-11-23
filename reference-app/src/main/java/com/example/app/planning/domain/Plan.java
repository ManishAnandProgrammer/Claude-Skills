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

    @Column(name = "done_on")
    private LocalDate doneOn;

    public void reschedule(LocalDate newStartsOn, LocalDate newEndsOn) {
        requirePlanned();
        if (newEndsOn.isBefore(newStartsOn)) {
            throw new IllegalStateException("A plan can't end on %s, before it starts on %s".formatted(newEndsOn, newStartsOn));
        }
        startsOn = newStartsOn;
        endsOn = newEndsOn;
    }

    private void requirePlanned() {
        if (status == PlanStatus.DONE) {
            throw AppException.of(ErrorCode.PLAN_DONE, getId());
        }
        if (status == PlanStatus.CANCELLED) {
            throw AppException.of(ErrorCode.PLAN_CANCELLED, getId());
        }
    }

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
