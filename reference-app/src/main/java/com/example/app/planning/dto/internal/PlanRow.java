package com.example.app.planning.dto.internal;

import com.example.app.planning.domain.PlanStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PlanRow(UUID id, UUID todoId, UUID ownerId, LocalDate startsOn, LocalDate endsOn, PlanStatus status,
                      LocalDate doneOn, Instant createdAt, Instant updatedAt, UUID createdBy, UUID updatedBy, Long version) {
}
