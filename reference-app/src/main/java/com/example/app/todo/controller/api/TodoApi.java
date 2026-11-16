package com.example.app.todo.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.todo.dto.request.TimeEntryCreateRequest;
import com.example.app.todo.dto.request.TimeEntryFilter;
import com.example.app.todo.dto.request.TimeEntryUpdateRequest;
import com.example.app.todo.dto.request.TodoCreateRequest;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.request.TodoStatusRequest;
import com.example.app.todo.dto.request.TodoTagsRequest;
import com.example.app.todo.dto.request.TodoUpdateRequest;
import com.example.app.todo.dto.response.TimeEntryResponse;
import com.example.app.todo.dto.response.TodoResponse;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "Todos", description = """
        The signed-in user's todos, the time logged on them (/todos/{todoId}/time-entries) and the tags \
        they carry (/todos/{id}/tags). Another user's todo, list or tag answers 404, except a todo you are \
        the requester of: you may read it (GET /todos/{id}), and every other call on it is 403 TODO_NOT_YOURS.""")
public interface TodoApi {

    // A piece marker can't sit inside a text block, so the create's 404 text starts with this constant:
    // generated code knows whether a project has tenancy and writes the description as one text block.
    // tenancy:begin
    // String REQUESTER_NOT_FOUND = "TENANT_MEMBER_NOT_FOUND (requesterId is no member of your organisation, or has left it); ";
    // tenancy:else
    String REQUESTER_NOT_FOUND = "USER_NOT_FOUND (requesterId is no account); ";
    // tenancy:end
    /** Every write of a todo, and its time entries, refuse its requester, who may read the todo itself. */
    String NOT_YOURS = "TODO_NOT_YOURS (you are its requester: you may read the todo, only its owner acts on it)";

    @Operation(summary = "Your todos, in batches", description = """
            Cursor-paged: the first call returns a batch and pageInfo.nextCursor; send it as after to \
            get the next batch, until hasMore is false. size sets the batch (default and maximum from \
            configuration). Filters: ids (find by ids: unknown or another user's ids are simply absent), \
            todoListId, tagId (todos carrying that tag), status, priority, dueOnOrBefore (yyyy-MM-dd, with \
            sort DUE_DATE only) and query (a case-insensitive part of the title). Ordered by sort: \
            DUE_DATE (the default; no due date last) or NEWEST; a cursor only continues the order it came \
            from. Rows carry the tags (id and name) and leave out the notes; GET /todos/{id} has them.""")
    @ApiResponse(responseCode = "200", description = "One batch, with pageInfo {nextCursor, hasMore}")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (size, query, after or the number of ids out of range, an empty sort; dueOnOrBefore with \
            sort NEWEST, reported on dueOnOrBefore); REQUEST_PARAMETER_INVALID (an unknown \
            status, priority or sort, a malformed date, id, todoListId or tagId, a non-numeric size, or an \
            after that is tampered or from another sort; see errors)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    CursorWrapper<TodoSummaryResponse> all(@ParameterObject TodoFilter filter);

    @Operation(summary = "One of your todos, or one you asked for", description = """
            With its list's name and who asked for it. Its requester reads it too; only its owner changes it.""")
    @ApiResponse(responseCode = "200", description = "The todo")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND (missing, or another user's you didn't ask for)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:begin
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY, MEDIA_STORAGE_UNAVAILABLE (the files' read URLs)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:else
    // @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    ObjectWrapper<TodoResponse> get(UUID id);

    @Operation(summary = "Create a todo", description = """
            Created OPEN, in one of your lists, carrying the tags in tagIds (your own tags, up to a \
            configured number; none when absent), with requesterId (who asked for it; optional). Its response \
            shows the list by name.""")
    @ApiResponse(responseCode = "201", description = "Created")
    // media:begin
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (see errors; tagIds over the maximum or holding null), REQUEST_UNREADABLE \
            (a malformed id); UPLOAD_FILE_COUNT_OUT_OF_RANGE (attachmentKeys outside the purpose's minFiles \
            and maxFiles)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:else
    // @ApiResponse(responseCode = "400", description = """
    //         VALIDATION_FAILED (see errors; tagIds over the maximum or holding null), REQUEST_UNREADABLE \
    //         (a malformed id)""",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:begin
    @ApiResponse(responseCode = "404", description = REQUESTER_NOT_FOUND + """
            TODO_LIST_NOT_FOUND, TAG_NOT_FOUND (the list or a tag is missing, or another user's); TODO_LIST_GONE, \
            TAG_GONE (deleted while the todo was being saved); UPLOAD_POLICY_NOT_FOUND (no upload policy for \
            the todos' attachments: an operator's setup is missing)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:else
    // @ApiResponse(responseCode = "404", description = REQUESTER_NOT_FOUND + """
    //         TODO_LIST_NOT_FOUND, TAG_NOT_FOUND (the list or a tag is missing, or another user's); TODO_LIST_GONE, \
    //         TAG_GONE (deleted while the todo was being saved)""",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    // media:begin
    @ApiResponse(responseCode = "409", description = """
            UPLOAD_NOT_AVAILABLE (an attachment key is unknown, another user's or purpose's, already attached \
            or expired: upload the file again)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    // media:begin
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY, MEDIA_STORAGE_UNAVAILABLE (the files' read URLs)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:else
    // @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    ObjectWrapper<TodoResponse> create(TodoCreateRequest request);

    // media:begin
    @Operation(summary = "Edit a todo", description = """
            Replaces title, notes, priority, due date, list (a different list moves it) and requesterId (absent \
            or null: nobody; the person it names already is kept even after they left, a new one is checked). \
            The status changes only through PATCH .../status, the tags only through PUT .../tags. Send the \
            version you last read; a stale one is refused with 409 OPTIMISTIC_LOCK_CONFLICT. Files are added \
            (addAttachmentKeys) and removed (removeAttachmentIds) here; a change of its files alone also \
            increments the version.""")
    // media:else
    // @Operation(summary = "Edit a todo", description = """
    //         Replaces title, notes, priority, due date, list (a different list moves it) and requesterId (absent \
    //         or null: nobody; the person it names already is kept even after they left, a new one is checked). \
    //         The status changes only through PATCH .../status, the tags only through PUT .../tags. Send the \
    //         version you last read; a stale one is refused with 409 OPTIMISTIC_LOCK_CONFLICT.""")
    // media:end
    @ApiResponse(responseCode = "200", description = "Updated; the version increments when something changed")
    // media:begin
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID; \
            UPLOAD_FILE_COUNT_OUT_OF_RANGE (its files would end outside the purpose's minFiles and maxFiles)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:else
    // @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = NOT_YOURS,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:begin
    @ApiResponse(responseCode = "404", description = REQUESTER_NOT_FOUND + """
            TODO_NOT_FOUND, TODO_LIST_NOT_FOUND, TODO_LIST_GONE (the new list deleted while the todo was being \
            saved), TODO_ATTACHMENT_NOT_FOUND (a removeAttachmentIds entry is not one of its files); \
            UPLOAD_POLICY_NOT_FOUND (no upload policy for the todos' attachments: an operator's setup is missing)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:else
    // @ApiResponse(responseCode = "404", description = REQUESTER_NOT_FOUND + """
    //         TODO_NOT_FOUND, TODO_LIST_NOT_FOUND, TODO_LIST_GONE (the new list deleted while the todo was being \
    //         saved)""",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    // media:begin
    @ApiResponse(responseCode = "409", description = """
            OPTIMISTIC_LOCK_CONFLICT, UPLOAD_NOT_AVAILABLE (an addAttachmentKeys entry can't be attached: upload \
            the file again)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:else
    // @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    // media:begin
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY, MEDIA_STORAGE_UNAVAILABLE (the files' read URLs)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:else
    // @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    ObjectWrapper<TodoResponse> update(UUID id, TodoUpdateRequest request);

    @Operation(summary = "Complete or reopen a todo", description = """
            DONE sets completedAt; OPEN clears it. Asking for the status it already has changes \
            nothing. Send the version you last read.""")
    @ApiResponse(responseCode = "200", description = "The todo in its new status")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE (an unknown status), REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = NOT_YOURS,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:begin
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY, MEDIA_STORAGE_UNAVAILABLE (the files' read URLs)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:else
    // @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    ObjectWrapper<TodoResponse> changeStatus(UUID id, TodoStatusRequest request);

    @Operation(summary = "Replace a todo's tags", description = """
            Makes tagIds (your own tags, up to a configured number) its tags, as a whole: [] removes them \
            all. Send the version you last read; a stale one is refused with 409 OPTIMISTIC_LOCK_CONFLICT. \
            Deleting a tag removes it from its todos without changing their version, so a replace that \
            drops a tag being deleted at that moment is refused with the same 409: reload and send it \
            again.""")
    @ApiResponse(responseCode = "200", description = "The todo with its tags; the version increments when they changed")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (see errors: tagIds or version missing, tagIds over the maximum or holding \
            null), REQUEST_UNREADABLE (a malformed tag id), REQUEST_PARAMETER_INVALID (the id is not a \
            UUID)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = NOT_YOURS,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND, TAG_NOT_FOUND (missing, or another user's); TAG_GONE (deleted while the tags were being saved)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT (a stale version, or a dropped tag deleted meanwhile)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:begin
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY, MEDIA_STORAGE_UNAVAILABLE (the files' read URLs)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:else
    // @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // media:end
    ObjectWrapper<TodoResponse> replaceTags(UUID id, TodoTagsRequest request);

    @Operation(summary = "Delete a todo", description = """
            Only a todo without time entries or plans: delete them first, so logged (billable) time is \
            never removed as a side effect. Its tags are taken off it; the tags themselves stay.""")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = NOT_YOURS,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TODO_IN_USE (it has time entries or plans)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void delete(UUID id);

    @Operation(summary = "A todo's time entries, in batches", description = """
            Cursor-paged: the first call returns a batch and pageInfo.nextCursor; send it as after to \
            get the next batch, until hasMore is false. size sets the batch (default and maximum from \
            configuration). Filters: ids (find by ids: unknown ids, or another todo's, are simply \
            absent); and, all inclusive, spentOnFrom and spentOnTo (yyyy-MM-dd, within configured \
            earliest and latest dates), minMinutes and maxMinutes (from 1 to the configured maximum of \
            an entry), and billable; a range whose \
            lower bound is above its upper one is a 400. Ordered by sort: SPENT_ON (the default; the \
            most recent day first) or MINUTES (the longest first); a cursor only continues the order it \
            came from.""")
    @ApiResponse(responseCode = "200", description = "One batch, with pageInfo {nextCursor, hasMore}")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (size, after, minMinutes, maxMinutes or the number of ids out of range; \
            spentOnFrom after spentOnTo, or minMinutes above maxMinutes, naming the upper bound; a spentOn \
            bound without sort SPENT_ON, or a minutes bound without sort MINUTES, naming that bound); \
            REQUEST_PARAMETER_INVALID (a malformed id or date, a non-numeric size or minutes, a billable \
            that is not true or false, an unknown sort, or an after that is tampered or from another \
            sort; see errors)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = NOT_YOURS,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND (missing, or another user's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    CursorWrapper<TimeEntryResponse> timeEntries(UUID todoId, @ParameterObject TimeEntryFilter filter);

    @Operation(summary = "One time entry of a todo")
    @ApiResponse(responseCode = "200", description = "The time entry")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (an id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = NOT_YOURS,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND, TIME_ENTRY_NOT_FOUND (missing, or not on this todo)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TimeEntryResponse> getTimeEntry(UUID todoId, UUID id);

    @Operation(summary = "Log time on a todo", description = """
            minutes from 1 to a configured maximum. spentOn is a calendar day in the user's own time zone, \
            stored as sent: the client keeps it from being in the future; the server refuses only a day \
            that is in the future everywhere (after today in UTC plus a configured tolerance). A blank note \
            is stored as none.""")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = NOT_YOURS,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND (missing, or another user's); TODO_GONE (deleted while the entry was being saved)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TimeEntryResponse> createTimeEntry(UUID todoId, TimeEntryCreateRequest request);

    @Operation(summary = "Edit a time entry", description = """
            Replaces minutes, spentOn, note and billable, with the rules of logging time. Send the version \
            you last read; a stale one is refused with 409 OPTIMISTIC_LOCK_CONFLICT.""")
    @ApiResponse(responseCode = "200", description = "Updated; the version increments when something changed")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = NOT_YOURS,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND, TIME_ENTRY_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TimeEntryResponse> updateTimeEntry(UUID todoId, UUID id, TimeEntryUpdateRequest request);

    @Operation(summary = "Delete a time entry")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (an id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = NOT_YOURS,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TODO_NOT_FOUND, TIME_ENTRY_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT (it changed while being deleted)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void deleteTimeEntry(UUID todoId, UUID id);
}
