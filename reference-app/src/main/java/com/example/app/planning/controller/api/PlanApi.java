package com.example.app.planning.controller.api;

import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.planning.dto.request.PlanCreateRequest;
import com.example.app.planning.dto.request.PlanFilter;
import com.example.app.planning.dto.request.PlanStatusRequest;
import com.example.app.planning.dto.request.PlanUpdateRequest;
import com.example.app.planning.dto.response.PlanResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "Plans", description = """
        Days the signed-in user sets aside for one of their todos. A user's plans never share a day. \
        Another user's plan answers 404.""")
public interface PlanApi {

    @Operation(summary = "Your plans, in batches", description = """
            Cursor-paged: the first call returns a batch and pageInfo.nextCursor; send it as after to get \
            the next batch, until hasMore is false. size sets the batch (default and maximum from \
            configuration). Filters: ids (find by ids: unknown or another user's ids are simply absent), \
            todoId (the plans of one of your todos) and plannedFrom/plannedTo (the plans sharing a day with \
            the range; either end alone is open on the other side; with sort STARTS_ON only). Ordered by \
            sort: STARTS_ON (the default; calendar order) or NEWEST; a cursor only continues the order it \
            came from. Each plan carries its todo's id and title.""")
    @ApiResponse(responseCode = "200", description = "One batch, with pageInfo {nextCursor, hasMore}")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (size, after or the number of ids out of range, an empty sort, a planned \
            day out of the configured dates, plannedFrom after plannedTo, a planned day under sort \
            NEWEST); REQUEST_PARAMETER_INVALID (a malformed id, todoId or planned day, a non-numeric \
            size, an unknown sort, or an after that is tampered or from another sort; see errors)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    CursorWrapper<PlanResponse> all(@ParameterObject PlanFilter filter);

    @Operation(summary = "One of your plans", description = "With its todo's id and title.")
    @ApiResponse(responseCode = "200", description = "The plan")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "PLAN_NOT_FOUND (missing, or another user's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<PlanResponse> get(UUID id);

    @Operation(summary = "Plan days for a todo", description = """
            From startsOn to endsOn, both inclusive (one day: the same date twice), between a configured \
            earliest and latest date. The days must be free: a plan that shares even one day with another of yours is refused \
            with 409 PLAN_OVERLAPS (a plan ending on the 22nd and one starting on the 22nd overlap; one \
            starting on the 23rd doesn't). The todo must be open: a plan for a DONE todo is refused with \
            409 PLAN_TODO_DONE. To move its days, PUT the new ones.""")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (a missing field, a date outside the configured earliest and latest ones, or \
            endsOn before startsOn or too far after it, reported on endsOn; see errors), REQUEST_UNREADABLE (a malformed id \
            or date)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND (missing, or another user's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "PLAN_OVERLAPS (it shares a day with another of your plans); PLAN_TODO_DONE (the todo is done)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<PlanResponse> create(PlanCreateRequest request);

    @Operation(summary = "Move a plan's days", description = """
            Both ends at once, with the version you last read, under the same rules as the create: between \
            the configured dates, at most a configured number of days, and free of your other plans (409 \
            PLAN_OVERLAPS; the plan's own old days don't count). Its todo stays the same, and must still be open, \
            as on the create (409 PLAN_TODO_DONE). A DONE or CANCELLED plan is final: 409 PLAN_DONE or \
            PLAN_CANCELLED, whatever the new days and its todo's state.""")
    @ApiResponse(responseCode = "200", description = "The plan with its new days and version")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (a missing field, a date outside the configured earliest and latest ones, or \
            endsOn before startsOn or too far after it, reported on endsOn; see errors), REQUEST_UNREADABLE (a \
            malformed date), REQUEST_PARAMETER_INVALID (the id is not a UUID)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "PLAN_NOT_FOUND (missing, or another user's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = """
            OPTIMISTIC_LOCK_CONFLICT (the version is stale); PLAN_DONE, PLAN_CANCELLED (the plan is final); \
            PLAN_TODO_DONE (its todo is done); PLAN_OVERLAPS (the new days share one with another of your plans)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<PlanResponse> update(UUID id, PlanUpdateRequest request);

    @Operation(summary = "Mark a plan done, or cancel it", description = """
            A PLANNED plan becomes DONE, on the day you give in doneOn (at most today, with a configured \
            tolerance, and never before its first day), or CANCELLED, which frees its days. Both are final: \
            any other move, a move to the current status included, is 409 PLAN_STATUS_CHANGE_INVALID.""")
    @ApiResponse(responseCode = "200", description = "The plan with its new status and version")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (a missing status or version; doneOn missing with DONE or sent with CANCELLED, \
            after today or before the configured earliest date; see errors); PLAN_DONE_ON_BEFORE_STARTS_ON \
            (doneOn before the plan's first day); REQUEST_UNREADABLE (an unknown status, a malformed date); \
            REQUEST_PARAMETER_INVALID (the id is not a UUID)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "PLAN_NOT_FOUND (missing, or another user's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = """
            OPTIMISTIC_LOCK_CONFLICT (the version is stale); PLAN_STATUS_CHANGE_INVALID (a move the lifecycle \
            doesn't allow)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<PlanResponse> changeStatus(UUID id, PlanStatusRequest request);

    @Operation(summary = "Delete a plan", description = "In any status. Its days are free again at once.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "PLAN_NOT_FOUND (missing, or another user's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT (another request deleted it meanwhile)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void delete(UUID id);
}
