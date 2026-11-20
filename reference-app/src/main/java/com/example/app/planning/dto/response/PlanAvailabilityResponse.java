package com.example.app.planning.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "Your days from from to to, as free and taken spans in calendar order")
public record PlanAvailabilityResponse(
        @Schema(description = "The first day asked for", example = "2026-11-16")
        LocalDate from,
        @Schema(description = "The last day asked for", example = "2026-11-22")
        LocalDate to,
        @Schema(description = "Every day from from to to, in one span each, in order")
        List<PlanAvailabilitySpan> spans) {

    public PlanAvailabilityResponse {
        spans = List.copyOf(spans);
    }
}
