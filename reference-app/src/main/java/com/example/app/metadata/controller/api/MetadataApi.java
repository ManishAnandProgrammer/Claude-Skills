package com.example.app.metadata.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.metadata.dto.request.MetadataUpdateRequest;
import com.example.app.metadata.dto.response.MetadataResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

@Tag(name = "Configuration metadata", description = """
        Application-wide configuration, one value per fixed key, for who holds METADATA_MANAGE. Only the fixed \
        keys declared in the application exist here. Configuration that belongs to one record (a \
        store's opening hours) is read and replaced through that record's own endpoint \
        (PUT /<resources>/{id}/<configuration>), which checks the record's rules. Values are created by \
        the application; this API reads and replaces them.""")
public interface MetadataApi {

    @Operation(summary = "A configuration", description = "The value with every default filled in, and the version to send back.")
    @ApiResponse(responseCode = "200", description = "The configuration")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (without METADATA_MANAGE)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = """
            METADATA_KEY_UNKNOWN (no fixed key of that name; a per-record configuration is not reached \
            here); METADATA_NOT_FOUND (declared, but not set up yet)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<MetadataResponse> get(
            @Parameter(
                    // example:begin
                    example = "TODO_LIST_DEFAULTS",
                    // example:end
                    description = "A fixed key")
            String key);

    @Operation(summary = "Replace a configuration", description = """
            Send the whole value and the version you last read; if it changed since, the update is \
            refused with 409 OPTIMISTIC_LOCK_CONFLICT. Reload and try again. A value equal to the stored \
            one changes nothing, not even the version.""")
    @ApiResponse(responseCode = "200", description = "Replaced; the version increments when the value changed")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (a field breaks its rule, or value or version is missing; see errors); \
            REQUEST_UNREADABLE (not JSON, a field of the wrong type, an unknown field; see errors)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (without METADATA_MANAGE)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "METADATA_KEY_UNKNOWN, METADATA_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<MetadataResponse> update(
            @Parameter(
                    // example:begin
                    example = "TODO_LIST_DEFAULTS",
                    // example:end
                    description = "A fixed key")
            String key,
            MetadataUpdateRequest request);
}
