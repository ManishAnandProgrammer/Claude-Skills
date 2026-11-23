package com.example.app.user.controller.api;

// tenancy:begin
// tenancy:else
import com.example.app.common.wrapper.BoundedListWrapper;
// tenancy:end
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.request.PasswordChangeRequest;
// tenancy:begin
// tenancy:else
import com.example.app.user.dto.request.UserFilter;
import com.example.app.user.dto.request.UserStatusRequest;
// tenancy:end
import com.example.app.user.dto.response.AccessResponse;
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

    // session-version:begin
    String ACCESS_TOKENS_END = "every access token is refused from its next request on";
    // session-version:else
    // String ACCESS_TOKENS_END = "every access token already issued expires within its 15 minutes";
    // session-version:end

    @Operation(summary = "The signed-in user's account")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND (the account no longer exists)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<UserResponse> me();

    @Operation(summary = "What you may do: your menu areas and your permissions", description = """
            What the server grants you now: call it again after a token refresh, or when a request is refused \
            with 403. A client shows a menu \
            item by its area, a tab or a button by its permission (or by a record's allowedActions), and never \
            decides which permissions make an area. Every request is checked by the server whatever the client \
            shows; which role holds which permission is never published.""")
    @ApiResponse(responseCode = "200", description = "Your areas and permissions")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<AccessResponse> access();

    @Operation(summary = "Change your password", description = """
            Needs the current password. The new one follows its field's rules. Every session of the account ends: every refresh token is revoked, on every device, \
            and\s""" + ACCESS_TOKENS_END + """
            . The answer is new tokens for this device. A wrong current password counts as a failed login of \
            the account: the same per-account limit applies (5 a minute).""")
    @ApiResponse(responseCode = "200", description = "Changed; new tokens for this device")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, USER_PASSWORD_INCORRECT (the current password \
            doesn't match: errors points at #/currentPassword), USER_PASSWORD_BREACHED""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // tenancy:begin
    // @ApiResponse(responseCode = "403", description = """
    //         AUTH_ACCOUNT_SUSPENDED (the account was suspended meanwhile); ACCESS_DENIED (an impersonation \
    //         never changes the member's password)""",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // tenancy:else
    @ApiResponse(responseCode = "403", description = "AUTH_ACCOUNT_SUSPENDED (the account was suspended meanwhile)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // tenancy:end
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

    @Operation(summary = "Find an account by its email address (ACCOUNT_READ)", description = """
            The account with this exact address (ignoring case and surrounding spaces), or an empty list: its \
            id, status and version, which a status change takes.""")
    @ApiResponse(responseCode = "200", description = "The account, or an empty list")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors: email missing or too long)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    BoundedListWrapper<UserResponse> lookUp(UserFilter filter);

    // roles:begin
    // @Operation(summary = "Suspend or reactivate an account (ACCOUNT_STATUS_CHANGE)", description = """
    //         SUSPENDED ends every session of the account at once (every refresh token revoked, and\s"""
    //         + ACCESS_TOKENS_END + """
    //         ) and refuses it every new one: login answers 403 AUTH_ACCOUNT_SUSPENDED. ACTIVE lets it \
    //         sign in again; the sessions the suspension ended stay ended. Never your own account; your account \
    //         must hold ACCOUNT_STATUS_CHANGE now (read under the change's lock), never an account that holds a \
    //         permission you don't, and never when no active account would then hold ROLES_MANAGE or ACCESS_GRANT \
    //         for good.""")
    // @ApiResponse(responseCode = "200", description = "The account with its new status and version")
    // @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID (a malformed id)",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // @ApiResponse(responseCode = "403", description = """
    //         ACCESS_DENIED (without ACCOUNT_STATUS_CHANGE, or not an ACTIVE account holding it now), USER_OWN_STATUS_CHANGE (your own account: another ADMIN \
    //         changes it), ACCESS_TARGET_HOLDS_MORE (the account holds a permission you don't)""",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT (a stale version), ACCESS_LAST_MANAGER",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
    //         content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // roles:else
    @Operation(summary = "Suspend or reactivate an account (ACCOUNT_STATUS_CHANGE)", description = """
            SUSPENDED ends every session of the account at once (every refresh token revoked, and\s"""
            + ACCESS_TOKENS_END + """
            ) and refuses it every new one: login answers 403 AUTH_ACCOUNT_SUSPENDED. ACTIVE lets it \
            sign in again; the sessions the suspension ended stay ended. Never your own account; your account \
            must be ACTIVE and its role hold ACCOUNT_STATUS_CHANGE now, whatever your token says, so the last \
            active account holding it is never suspended.""")
    @ApiResponse(responseCode = "200", description = "The account with its new status and version")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID (a malformed id)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = """
            ACCESS_DENIED (without ACCOUNT_STATUS_CHANGE, or not an ACTIVE account holding it now), USER_OWN_STATUS_CHANGE (your own account: another ADMIN \
            changes it)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT (a stale version)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    // roles:end
    ObjectWrapper<UserResponse> changeStatus(@Parameter(description = "The account's id") UUID id, UserStatusRequest request);
    // tenancy:end
}
