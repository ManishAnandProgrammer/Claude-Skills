package com.example.app.todo.dto.request;

import com.example.app.common.limits.ConfiguredMax;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.limits.NotAfterToday;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@Schema(description = "A time entry's new content")
public record TimeEntryUpdateRequest(
        @Schema(description = "Minutes spent, from 1 to a configured maximum", example = "45")
        @NotNull @Min(1) @ConfiguredMax("time-entry.max-minutes")
        Integer minutes,

        @Schema(description = "The day the time was spent (yyyy-MM-dd), in your own calendar; not in the future", example = "2026-11-12")
        @NotNull @NotAfterToday
        LocalDate spentOn,

        @Schema(description = "What the time went on; null or blank removes it (returned as null)", example = "Pairing on the import")
        @MaxLength("time-entry.note")
        String note,

        @Schema(description = "Whether the time is billed to a client", example = "false")
        @NotNull
        Boolean billable,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {
}
