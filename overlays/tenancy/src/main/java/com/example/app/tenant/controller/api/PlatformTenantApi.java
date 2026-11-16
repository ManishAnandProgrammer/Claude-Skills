package com.example.app.tenant.controller.api;

import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.tenant.dto.request.PlatformAuditFilter;
import com.example.app.tenant.dto.request.TenantCreateRequest;
import com.example.app.tenant.dto.request.TenantFilter;
import com.example.app.tenant.dto.request.TenantStatusRequest;
import com.example.app.tenant.dto.response.PlatformAuditResponse;
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
            becomes ACTIVE once it is provisioned: in row mode right after the creating transaction commits, \
            before this answer is written; with a schema per tenant by the provisioning job, once its tables \
            exist (usually within seconds). Until then its members' login answers 403 TENANT_NOT_ACTIVE. The \
            answer is the tenant as it is then, with the version a status change sends back.""")
    @ApiResponse(responseCode = "201", description = "Created: ACTIVE when already provisioned, else PENDING")
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
            Starts its provisioning. The answer is the tenant as it is then, with its current version: row \
            mode has provisioned it before the answer (ACTIVE, unless provisioning failed and it stays PENDING \
            until approved again); with a schema per tenant it stays PENDING until its tables exist, usually \
            seconds. Approving a tenant that is no longer PENDING is 409. A billing module's payment webhook \
            would approve the same way.""")
    @ApiResponse(responseCode = "202", description = "Approved: ACTIVE when already provisioned, else PENDING until provisioning follows")
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

    @Operation(summary = "A tenant's support history, in batches", description = """
            Every impersonation and direct edit by platform staff inside the tenant, newest first. Pass \
            pageInfo.nextCursor as after for the next batch; the batch size defaults and is bounded by \
            configuration.""")
    @ApiResponse(responseCode = "200", description = "A batch of entries")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_PARAMETER_INVALID (a bad id or cursor)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    CursorWrapper<PlatformAuditResponse> audit(@Parameter(description = "Tenant id") UUID id,
                                               @ParameterObject PlatformAuditFilter filter);
}
