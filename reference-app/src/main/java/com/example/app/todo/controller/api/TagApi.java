package com.example.app.todo.controller.api;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.todo.dto.request.TagCreateRequest;
import com.example.app.todo.dto.request.TagFilter;
import com.example.app.todo.dto.request.TagMergeRequest;
import com.example.app.todo.dto.request.TagUpdateRequest;
import com.example.app.todo.dto.response.TagResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "Tags", description = "The signed-in user's tags. Another user's tag answers 404.")
public interface TagApi {

    @Operation(summary = "A page of your tags", description = """
            Paged with page and size (the default and the maximums come from configuration; beyond them \
            is a 400), ordered by sort (NAME, the default, or NEWEST), filtered by ids (find by ids: \
            unknown or another user's ids are simply absent), status and query (a case-insensitive part \
            of the name). status is the dropdown's: ACTIVE, INACTIVE or ALL; here \
            the default is ALL, since this page manages every tag, while the dropdown defaults to \
            ACTIVE.""")
    @ApiResponse(responseCode = "200", description = "One page, with pageInfo")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (page, size, query or the number of ids out of range); \
            REQUEST_PARAMETER_INVALID (an unknown sort or status, a non-numeric page or size, a malformed \
            id; see errors)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    PaginationWrapper<TagResponse> all(@ParameterObject TagFilter filter);

    @Operation(summary = "One of your tags")
    @ApiResponse(responseCode = "200", description = "The tag")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TAG_NOT_FOUND (missing, or another user's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TagResponse> get(UUID id);

    @Operation(summary = "Create a tag", description = "Created active. The colour is optional, as #RRGGBB; empty means none.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TAG_NAME_TAKEN (ignoring case and surrounding spaces)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TagResponse> create(TagCreateRequest request);

    @Operation(summary = "Edit a tag", description = """
            Replaces name, colour (empty or null removes it) and active. Send the version you last read; if the tag changed since, \
            the update is refused with 409 OPTIMISTIC_LOCK_CONFLICT. Reload and try again.""")
    @ApiResponse(responseCode = "200", description = "Updated; the version increments when something changed")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TAG_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TAG_NAME_TAKEN, OPTIMISTIC_LOCK_CONFLICT",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TagResponse> update(UUID id, TagUpdateRequest request);

    @Operation(summary = "Delete a tag", description = """
            Never blocked by the todos carrying it: it is removed from them, and their version doesn't \
            change.""")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TAG_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT (it changed while being deleted)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void delete(UUID id);

    @Operation(summary = "Merge another of your tags into this one", description = """
            Every todo carrying sourceTagId carries this tag instead (once, when it carried both), and the \
            source tag is deleted, all at once: either all of it happens or nothing does. Each todo whose tags \
            changed gets a new version. This tag itself stays as it was, its version too. A todo tagged with \
            the source while the merge runs is refused with 404 TAG_GONE.""")
    @ApiResponse(responseCode = "200", description = "Merged: this tag")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (sourceTagId missing; see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID (the \
            id is not a UUID), TAG_MERGE_INTO_ITSELF (sourceTagId is this tag)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TAG_NOT_FOUND (this tag or the source: missing, or another user's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TagResponse> merge(UUID id, TagMergeRequest request);

    @Operation(summary = "Options for the Tag dropdown", description = """
            Your tags as {value, label}, ordered by name (sortDirection DESC reverses it), in batches: \
            pageInfo.nextCursor goes back as after for the next batch; size sets the batch (default \
            and maximum from configuration). status picks ACTIVE tags (the default), INACTIVE ones or \
            ALL. Narrow it with query (a case-insensitive part of the name).""")
    @ApiResponse(responseCode = "200", description = "The options")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (size, query or after out of range); REQUEST_PARAMETER_INVALID (an unknown \
            status or sortDirection, a non-numeric size, or an after that is tampered or from the other \
            direction; see errors)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    CursorWrapper<DropdownItem> dropdown(@ParameterObject DropdownFilter filter);
}
