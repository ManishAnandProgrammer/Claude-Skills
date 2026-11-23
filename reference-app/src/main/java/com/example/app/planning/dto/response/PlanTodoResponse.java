package com.example.app.planning.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "The todo a plan is for")
public record PlanTodoResponse(
        @Schema(description = "Todo id", example = "0190a9c2-7d10-7b2c-8e3f-1a2b3c4d5e6f") UUID id,
        @Schema(description = "What to do", example = "Buy oat milk") String title) {
}
