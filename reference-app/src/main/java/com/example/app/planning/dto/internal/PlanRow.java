package com.example.app.planning.dto.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A plan as the list query reads it: the response's fields but the todo's title, which another
 * module owns and the service adds for the whole batch in one lookup.
 */
public record PlanRow(UUID id, UUID todoId, UUID ownerId, LocalDate startsOn, LocalDate endsOn,
                      Instant createdAt, Instant updatedAt, UUID createdBy, UUID updatedBy, Long version) {
}
