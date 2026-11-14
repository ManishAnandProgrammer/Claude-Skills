package com.example.app.tenant.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.tenant.dto.request.TenantCreateRequest;
import com.example.app.tenant.dto.request.TenantFilter;
import com.example.app.tenant.dto.request.TenantStatusRequest;
import com.example.app.tenant.dto.response.TenantResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "Platform: tenants", description = """
        The tenant register, for platform staff (a platform token from POST /platform/auth/login). Tenant \
        tokens are refused here with 401, as platform tokens are on every tenant endpoint. Inside a \
        tenant, staff act through an impersonation token or an audited direct edit.""")
public interface PlatformTenantApi {

    @Operation(summary = "Tenants, a page at a time", description = "Newest first unless sort says otherwise.")
    @ApiResponse(responseCode = "200", description = "A page of tenants")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_PARAMETER_INVALID",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    PaginationWrapper<TenantResponse> all(@ParameterObject TenantFilter filter);

    @Operation(summary = "One tenant")
    @ApiResponse(responseCode = "200", description = "The tenant")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TenantResponse> get(@Parameter(description = "Tenant id") UUID id);

    @Operation(summary = "Create a tenant", description = """
            Created PENDING and approved at once, with the owner (an existing account) as its ADMIN. It \
            becomes ACTIVE once its tables exist (provisioning runs in the background, usually within \
            seconds); until then its members' login answers 403 TENANT_NOT_ACTIVE.""")
    @ApiResponse(responseCode = "201", description = "Created, PENDING")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_OWNER_NOT_FOUND (no account with that email)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TenantResponse> create(TenantCreateRequest request);

    @Operation(summary = "Approve a registered tenant", description = """
            Starts its provisioning: it stays PENDING until its tables exist, then becomes ACTIVE (usually \
            within seconds). Approving an approved tenant again changes nothing. A billing module's payment \
            webhook would approve the same way.""")
    @ApiResponse(responseCode = "202", description = "Approved; provisioning follows")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TENANT_STATUS_CHANGE_INVALID (the tenant isn't PENDING)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TenantResponse> approve(@Parameter(description = "Tenant id") UUID id);

    @Operation(summary = "Suspend, reactivate, close or reject a tenant", description = """
            Takes effect at its members' next login, refresh or switch: within one access-token lifetime \
            (15 minutes). Every change is recorded with the staff member who made it.""")
    @ApiResponse(responseCode = "200", description = "Changed")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TENANT_STATUS_CHANGE_INVALID (the lifecycle doesn't allow it), OPTIMISTIC_LOCK_CONFLICT",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TenantResponse> changeStatus(@Parameter(description = "Tenant id") UUID id, TenantStatusRequest request);
}
