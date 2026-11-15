package com.example.app.user.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.request.ImpersonationRequest;
import com.example.app.user.dto.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "Platform: impersonation", description = """
        Platform staff acting as a member of a tenant, for support. Every request that reaches the \
        support-access policy is recorded in the platform audit trail with its reason, refused ones \
        included; one refused before it (an invalid body, a tenant that isn't ACTIVE, no such member) \
        is not.""")
public interface PlatformImpersonationApi {

    @Operation(summary = "Act as a tenant's member", description = """
            Returns a short-lived tenant token for the member (default: the ADMIN with the oldest \
            membership, not always the owner), with your platform account as its actor (claim act.sub). It \
            works on every tenant endpoint with the member's role; there is no refresh token. The application's support-access policy may refuse \
            it (by default it allows every request).""")
    @ApiResponse(responseCode = "201", description = "A token for the tenant; refreshToken is null")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = """
            TENANT_NOT_ACTIVE (with tenantStatus), SUPPORT_ACCESS_DENIED, AUTH_ACCOUNT_SUSPENDED (the member's \
            account is suspended: staff act as nobody who may not sign in)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "MEMBERSHIP_NOT_FOUND (no such member, or no ADMIN)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TokenResponse> impersonate(@Parameter(description = "Tenant id") UUID tenantId, ImpersonationRequest request);
}
