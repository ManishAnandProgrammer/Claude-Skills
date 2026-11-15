package com.example.app.user.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.request.UserStatusRequest;
import com.example.app.user.dto.response.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "Platform: accounts", description = """
        Platform staff suspending and reactivating accounts. Accounts are global (one person, any number of \
        tenants), so no tenant ADMIN does this: they add, change and remove memberships.""")
public interface PlatformUserApi {

    @Operation(summary = "Suspend or reactivate an account", description = """
            SUSPENDED ends every session of the account in every tenant at once (every refresh token revoked; \
            with the session version, every access token refused from the next request on, else within its \
            15 minutes) and refuses it every new one: login, refresh, switch-tenant and impersonation answer \
            403 AUTH_ACCOUNT_SUSPENDED. ACTIVE lets it sign in again; the sessions the suspension ended stay \
            ended. A person who is the last ADMIN with an ACTIVE account of an open tenant is not suspended: \
            make another member ADMIN first, or suspend the tenant.""")
    @ApiResponse(responseCode = "200", description = "The account with its new status and version")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID (a malformed id)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token, or a deactivated staff member's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = """
            OPTIMISTIC_LOCK_CONFLICT (a stale version), TENANT_MEMBER_LAST_ADMIN (the last active ADMIN of an \
            open tenant)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<UserResponse> changeStatus(@Parameter(description = "The account's id") UUID id, UserStatusRequest request);
}
