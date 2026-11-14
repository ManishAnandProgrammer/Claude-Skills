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
        Per-feature configuration, one value per key, for admins (role ADMIN). Only keys declared in the \
        application exist: fixed ones, and per-context ones whose name is a prefix plus the context's \
        id (SOME_CONFIG_7). Values are created by the application when their context is created; \
        this API reads and replaces them.""")
public interface MetadataApi {

    @Operation(summary = "A configuration", description = "The value with every default filled in, and the version to send back.")
    @ApiResponse(responseCode = "200", description = "The configuration")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (not an admin)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = """
            METADATA_KEY_UNKNOWN (no such key is declared); METADATA_NOT_FOUND (declared, but not set up \
            for this context)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<MetadataResponse> get(@Parameter(description = "The key", example = "SOME_CONFIG_7") String key);

    @Operation(summary = "Replace a configuration", description = """
            Send the whole value and the version you last read; if it changed since, the update is \
            refused with 409 OPTIMISTIC_LOCK_CONFLICT. Reload and try again. A value equal to the stored \
            one changes nothing, not even the version.""")
    @ApiResponse(responseCode = "200", description = "Replaced; the version increments when the value changed")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (a field breaks its rule, or value or version is missing; see failed); \
            REQUEST_UNREADABLE (not JSON, a field of the wrong type, an unknown field; see failed)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (not an admin)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "METADATA_KEY_UNKNOWN, METADATA_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<MetadataResponse> update(@Parameter(description = "The key", example = "SOME_CONFIG_7") String key,
                                           MetadataUpdateRequest request);
}
