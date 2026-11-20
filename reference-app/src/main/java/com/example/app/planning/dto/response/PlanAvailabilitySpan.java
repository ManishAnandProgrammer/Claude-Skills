package com.example.app.planning.dto.response;

import com.example.app.common.security.AllowedAction;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "A run of days, all free or all taken")
public record PlanAvailabilitySpan(
        @Schema(description = "The first day, inclusive", example = "2026-11-16")
        LocalDate startsOn,
        @Schema(description = "The last day, inclusive", example = "2026-11-18")
        LocalDate endsOn,
        @Schema(description = """
                Whether a new plan may take these days: when not, the reason the create would answer \
                (PLAN_OVERLAPS)""")
        AllowedAction plannable) {
}
