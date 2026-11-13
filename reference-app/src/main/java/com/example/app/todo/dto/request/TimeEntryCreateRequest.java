package com.example.app.todo.dto.request;

import com.example.app.common.limits.ConfiguredMax;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.limits.NotAfterToday;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@Schema(description = "Time spent on the todo")
public record TimeEntryCreateRequest(
        @Schema(description = "Minutes spent, from 1 to a configured maximum", example = "45")
        @NotNull @Min(1) @ConfiguredMax("time-entry.max-minutes")
        Integer minutes,

        @Schema(description = "The day the time was spent (yyyy-MM-dd), in your own calendar; not in the future", example = "2026-11-12")
        @NotNull @NotAfterToday
        LocalDate spentOn,

        @Schema(description = "What the time went on; null, absent or blank for none (returned as null)", example = "Pairing on the import")
        @MaxLength("time-entry.note")
        String note,

        @Schema(description = "Whether the time is billed to a client", example = "true")
        @NotNull
        Boolean billable) {
}
