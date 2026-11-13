package com.example.app.todo.controller.api;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.todo.dto.request.TodoListCreateRequest;
import com.example.app.todo.dto.request.TodoListFilter;
import com.example.app.todo.dto.request.TodoListUpdateRequest;
import com.example.app.todo.dto.response.TodoListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "Todo lists", description = "The signed-in user's todo lists. Another user's list answers 404.")
public interface TodoListApi {

    @Operation(summary = "A page of your todo lists", description = """
            Paged with page and size (the default and the maximums come from configuration; beyond them \
            is a 400), ordered by sort (NAME, the default, or NEWEST), filtered by query (a \
            case-insensitive part of the name).""")
    @ApiResponse(responseCode = "200", description = "One page, with pageInfo")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (page, size or query out of range), REQUEST_PARAMETER_INVALID (an unknown sort; see failed)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    PaginationWrapper<TodoListResponse> all(@ParameterObject TodoListFilter filter);

    @Operation(summary = "One of your todo lists")
    @ApiResponse(responseCode = "200", description = "The list")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_LIST_NOT_FOUND (missing, or another user's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TodoListResponse> get(UUID id);

    @Operation(summary = "Create a todo list")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see failed), REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TODO_LIST_NAME_TAKEN",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TodoListResponse> create(TodoListCreateRequest request);

    @Operation(summary = "Rename a todo list", description = """
            Send the version you last read; if the list changed since, the update is refused with \
            409 OPTIMISTIC_LOCK_CONFLICT. Reload and try again.""")
    @ApiResponse(responseCode = "200", description = "Updated; version incremented")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see failed), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_LIST_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TODO_LIST_NAME_TAKEN, OPTIMISTIC_LOCK_CONFLICT",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TodoListResponse> update(UUID id, TodoListUpdateRequest request);

    @Operation(summary = "Delete an empty todo list")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_LIST_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TODO_LIST_IN_USE (it still has todos), OPTIMISTIC_LOCK_CONFLICT (it changed while being deleted)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void delete(UUID id);

    @Operation(summary = "Options for the Todo list dropdown", description = """
            Your lists as {value, label}, ordered by name (sortDirection DESC reverses it), in batches: \
            pageInfo.nextCursor goes back as after for the next batch; size sets the batch (default \
            and maximum from configuration). Narrow it with query. Lists have no active flag, so a \
            status other than ACTIVE is refused with REQUEST_PARAMETER_INVALID.""")
    @ApiResponse(responseCode = "200", description = "The options")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (size or query out of range), REQUEST_PARAMETER_INVALID (status, an unknown sortDirection, or a cursor from the other direction)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    CursorWrapper<DropdownItem> dropdown(@ParameterObject DropdownFilter filter);
}
