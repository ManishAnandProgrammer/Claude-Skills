package com.example.app.planning.dto.response;

import com.example.app.common.security.AllowedAction;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the owner may do with this plan, decided by the rules its writes check (PlanPolicy): its state decides
 * each, so a client shows a button by this, never by the plan's status. Its delete is always open, so it isn't
 * here.
 */
@Schema(description = "What you may do with this plan; the server checks again on every call")
public record PlanAllowedActionsResponse(
        @Schema(description = """
                Whether you may give it new days (PUT): only while PLANNED (PLAN_DONE, PLAN_CANCELLED) and its todo \
                is open (PLAN_TODO_DONE)""")
        AllowedAction reschedule,
        @Schema(description = "Whether you may mark it DONE (PATCH .../status): only while PLANNED (PLAN_STATUS_CHANGE_INVALID)")
        AllowedAction markDone,
        @Schema(description = "Whether you may cancel it (PATCH .../status): only while PLANNED (PLAN_STATUS_CHANGE_INVALID)")
        AllowedAction cancel) {
}
