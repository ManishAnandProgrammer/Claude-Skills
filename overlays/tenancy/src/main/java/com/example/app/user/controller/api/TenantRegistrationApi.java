package com.example.app.user.controller.api;

import com.example.app.user.dto.request.TenantRegistrationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;

@Tag(name = "Tenant registration", description = "Organisations registering themselves, approved by platform staff")
@SecurityRequirements
public interface TenantRegistrationApi {

    @Operation(summary = "Register an organisation", description = """
            Creates the organisation PENDING, owned by the account with this email: a new account when the \
            email has none, the existing one when the password is its own. Platform staff approve or reject \
            it; until then the owner's login answers 403 TENANT_NOT_ACTIVE with tenantStatus PENDING. The \
            answer is the same 202 in every case, so it never tells whether an email has an account: a \
            wrong password for an existing account registers nothing.""")
    @ApiResponse(responseCode = "202", description = "Received; no body")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see failed), REQUEST_UNREADABLE, USER_PASSWORD_BREACHED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED; wait Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void register(TenantRegistrationRequest request, @Parameter(hidden = true) HttpServletRequest http);
}
