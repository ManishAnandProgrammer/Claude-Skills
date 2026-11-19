package com.example.app.todo.controller.api;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownFor;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.security.Permission;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.todo.dto.request.TodoListCreateRequest;
import com.example.app.todo.dto.request.TodoListFilter;
import com.example.app.todo.dto.request.TodoListUpdateRequest;
import com.example.app.todo.dto.response.TodoListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
            is a 400), ordered by sort (NAME, the default, or NEWEST), filtered by ids (find by ids: unknown \
            or another user's ids are simply absent) and query (a case-insensitive part of the name).""")
    @ApiResponse(responseCode = "200", description = "One page, with pageInfo")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (page, size, query or the number of ids out of range, an empty sort); \
            REQUEST_PARAMETER_INVALID (an unknown sort, a malformed id, a non-numeric page or size; see \
            errors)""",
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
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE",
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
    @ApiResponse(responseCode = "200", description = "Updated; the version increments when something changed")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID",
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
            Your lists as {value, label}, for the action for names (one dropdown serves every screen that \
            picks one), ordered by name (sortDirection DESC reverses it), in batches: \
            pageInfo.nextCursor goes back as after for the next batch; size sets the batch (default \
            and maximum from configuration). Narrow it with query. Lists have no active flag, so a \
            status other than ACTIVE is refused with REQUEST_PARAMETER_INVALID.""")
    @ApiResponse(responseCode = "200", description = "The options")
    @ApiResponse(responseCode = "400", description = """
            DROPDOWN_REACH_UNSUPPORTED (for reaches records other than your own: see errors); \
            REQUEST_PARAMETER_MISSING or REQUEST_PARAMETER_INVALID at for (none, or no permission); \
            VALIDATION_FAILED (size, query or after out of range); REQUEST_PARAMETER_INVALID (a status \
            other than ACTIVE, an unknown sortDirection, a non-numeric size, or an after that is \
            tampered or from the other direction; see errors)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (you don't hold the permission for names)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    CursorWrapper<DropdownItem> dropdown(
            @Parameter(name = DropdownFor.PARAMETER, required = true, example = "TODO_CREATE_OWN", description = """
                    The permission of the action the options serve (TODO_CREATE_OWN to pick a new todo's list, \
                    TODO_READ_OWN to filter your todos by one): 403 ACCESS_DENIED unless you hold it, and \
                    the options are the ones its reach covers""") Permission action,
            @ParameterObject DropdownFilter filter);
}
