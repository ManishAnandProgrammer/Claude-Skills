package com.example.app.user.controller.api;

import com.example.app.common.wrapper.ListWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.request.UserFilter;
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
        Platform staff finding, suspending and reactivating accounts. Accounts are global (one person, any \
        number of tenants), so no tenant ADMIN does this: they add, change and remove memberships.""")
public interface PlatformUserApi {

    @Operation(summary = "Find an account by its email address", description = """
            The account with this exact address (ignoring case and surrounding spaces), or an empty list: its \
            id, status and version, which a status change and an impersonation take. No role: an account's \
            roles are its memberships'.""")
    @ApiResponse(responseCode = "200", description = "The account, or an empty list")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors: email missing or too long)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token, or a deactivated staff member's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ListWrapper<UserResponse> lookUp(UserFilter filter);

    @Operation(summary = "Suspend or reactivate an account", description = """
            SUSPENDED ends every session of the account in every tenant at once (every refresh token revoked, \
            and\s""" + UserApi.ACCESS_TOKENS_END + """
            ) and refuses it every new one: login, refresh, switch-tenant and impersonation answer \
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
