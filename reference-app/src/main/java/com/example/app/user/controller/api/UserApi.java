package com.example.app.user.controller.api;

// tenancy:begin
// tenancy:else
import com.example.app.common.wrapper.ListWrapper;
// tenancy:end
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.request.PasswordChangeRequest;
// tenancy:begin
// tenancy:else
import com.example.app.user.dto.request.UserFilter;
import com.example.app.user.dto.request.UserStatusRequest;
// tenancy:end
import com.example.app.user.dto.response.TokenResponse;
import com.example.app.user.dto.response.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;
// tenancy:begin
// tenancy:else

import java.util.UUID;
// tenancy:end

@Tag(name = "Users", description = "The signed-in user's account")
public interface UserApi {

    @Operation(summary = "The signed-in user's account")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND (the account no longer exists)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<UserResponse> me();

    @Operation(summary = "Change your password", description = """
            Needs the current password. The new one follows signup's rules (at least 8 characters and at most \
            72 bytes, an upper-case letter, a lower-case letter, a digit and another character, not from a \
            known breach). Every session of the account ends: every refresh token is revoked, on every device \
            (with the session version, every access token too, from the next request on). The answer is new \
            tokens for this device. A wrong current password counts as a failed login of the account: the \
            same per-account limit applies (5 a minute).""")
    @ApiResponse(responseCode = "200", description = "Changed; new tokens for this device")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, USER_PASSWORD_INCORRECT (the current password \
            doesn't match: errors points at #/currentPassword), USER_PASSWORD_BREACHED""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "AUTH_ACCOUNT_SUSPENDED (the account was suspended meanwhile)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND (the account no longer exists)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT (the account changed meanwhile: try again)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED; wait Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TokenResponse> changePassword(PasswordChangeRequest request, @Parameter(hidden = true) HttpServletRequest http);
    // session-version:begin

    @Operation(summary = "Sign out everywhere", description = """
            Ends every session of the signed-in account: every refresh token is revoked, and every access \
            token issued so far, this one included, is refused from the next request on with 401 \
            SESSION_VERSION_STALE (on every device). Sign in again afterwards.""")
    @ApiResponse(responseCode = "204", description = "Signed out everywhere")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void endAllSessions();
    // session-version:end
    // tenancy:begin
    // tenancy:else

    @Operation(summary = "Find an account by its email address (ADMIN)", description = """
            The account with this exact address (ignoring case and surrounding spaces), or an empty list: its \
            id, status and version, which a status change takes.""")
    @ApiResponse(responseCode = "200", description = "The account, or an empty list")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors: email missing or too long)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (not an ADMIN)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ListWrapper<UserResponse> lookUp(UserFilter filter);

    @Operation(summary = "Suspend or reactivate an account (ADMIN)", description = """
            SUSPENDED ends every session of the account at once (every refresh token revoked; with the \
            session version, every access token refused from the next request on, else within its 15 \
            minutes) and refuses it every new one: login answers 403 AUTH_ACCOUNT_SUSPENDED. ACTIVE lets it \
            sign in again; the sessions the suspension ended stay ended. Never your own account; you must be \
            an ACTIVE ADMIN now, whatever your token says, so the last ADMIN is never suspended.""")
    @ApiResponse(responseCode = "200", description = "The account with its new status and version")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID (a malformed id)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = """
            ACCESS_DENIED (not an ACTIVE ADMIN now), USER_OWN_STATUS_CHANGE (your own account: another ADMIN \
            changes it)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT (a stale version)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<UserResponse> changeStatus(@Parameter(description = "The account's id") UUID id, UserStatusRequest request);
    // tenancy:end
}
