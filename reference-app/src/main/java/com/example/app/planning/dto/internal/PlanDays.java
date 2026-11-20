package com.example.app.planning.dto.internal;

import java.time.LocalDate;

/** The days a live plan holds, both inclusive, as the availability read takes them. */
public record PlanDays(LocalDate startsOn, LocalDate endsOn) {
}
