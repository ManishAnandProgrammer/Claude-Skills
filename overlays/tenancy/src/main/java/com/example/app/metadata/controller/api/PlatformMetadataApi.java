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

import java.util.UUID;

@Tag(name = "Platform: tenant configuration", description = """
        Platform staff changing a tenant's configuration directly, as its admin would. Every change is \
        recorded in the platform audit trail.""")
public interface PlatformMetadataApi {

    @Operation(summary = "Replace a tenant's fixed configuration key", description = """
            The same rules as PUT /metadata/{key} inside the tenant: the whole value and the version you \
            last read.""")
    @ApiResponse(responseCode = "200", description = "Replaced")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "TENANT_NOT_ACTIVE (with tenantStatus)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "METADATA_KEY_UNKNOWN, METADATA_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<MetadataResponse> update(@Parameter(description = "Tenant id") UUID tenantId,
                                           @Parameter(description = "A fixed key's name") String key,
                                           MetadataUpdateRequest request);
}
