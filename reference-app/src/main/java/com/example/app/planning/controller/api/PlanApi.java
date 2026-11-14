package com.example.app.planning.controller.api;

import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.planning.dto.request.PlanCreateRequest;
import com.example.app.planning.dto.request.PlanFilter;
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
            configuration). Filters: ids (find by ids: unknown or another user's ids are simply absent) \
            and todoId (the plans of one of your todos). Ordered by sort: STARTS_ON (the default; calendar \
            order) or NEWEST; a cursor only continues the order it came from. Each plan carries its todo's \
            id and title.""")
    @ApiResponse(responseCode = "200", description = "One batch, with pageInfo {nextCursor, hasMore}")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (size, after or the number of ids out of range); REQUEST_PARAMETER_INVALID \
            (a malformed id or todoId, a non-numeric size, an unknown sort, or an after that is tampered \
            or from another sort; see failed or parameter)""",
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
            starting on the 23rd doesn't). A plan never changes: to move it, delete it and create another.""")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (a missing field, a date outside the configured earliest and latest ones, or \
            endsOn before startsOn, named dateRangeOrdered; see failed), REQUEST_UNREADABLE (a malformed id \
            or date)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND (missing, or another user's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "PLAN_OVERLAPS (it shares a day with another of your plans)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<PlanResponse> create(PlanCreateRequest request);

    @Operation(summary = "Delete a plan", description = "Its days are free again at once.")
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
