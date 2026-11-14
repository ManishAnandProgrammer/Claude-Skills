package com.example.app.user.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.request.LoginRequest;
import com.example.app.user.dto.request.RefreshTokenRequest;
import com.example.app.user.dto.request.SignupRequest;
import com.example.app.user.dto.request.SwitchTenantRequest;
import com.example.app.user.dto.response.TokenResponse;
import com.example.app.user.dto.response.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

@Tag(name = "Sign-in", description = """
        Signup, login and the token lifecycle. Public. Every endpoint is rate limited per client IP; \
        login also per account (5 a minute), so repeated wrong passwords for one email make its \
        login answer 429 for a while.""")
@SecurityRequirements
public interface AuthApi {

    @Operation(summary = "Create an account", description = """
            Creates a USER account. The email (ASCII) is stored lower-case; the password needs at \
            least 8 characters and at most 72 bytes, with an upper-case letter, a lower-case letter, \
            a digit and another character, and must not appear in a known data breach (checked \
            against Have I Been Pwned without sending the password; skipped while that service is \
            unreachable or busy). Sign in afterwards with login.""")
    @ApiResponse(responseCode = "201", description = "Created; Location points at /api/{version}/users/me, in the version called")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see failed), REQUEST_UNREADABLE, USER_PASSWORD_BREACHED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "USER_EMAIL_TAKEN",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED; wait Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<ObjectWrapper<UserResponse>> signup(SignupRequest request, @Parameter(hidden = true) HttpServletRequest http);

    @Operation(summary = "Sign in", description = """
            Returns an access token (send as Authorization: Bearer) and a single-use refresh token, for \
            your most recently used tenant that is ACTIVE (switch-tenant picks another). An unknown email \
            and a wrong password get the same answer.""")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTH_INVALID_CREDENTIALS",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = """
            TENANT_MEMBERSHIP_REQUIRED (the account belongs to no tenant yet); TENANT_NOT_ACTIVE (none of \
            your tenants is ACTIVE; tenantStatus is your most recently used one's: PENDING means awaiting \
            provisioning or approval)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED; wait Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TokenResponse> login(LoginRequest request, @Parameter(hidden = true) HttpServletRequest http);

    @Operation(summary = "Exchange a refresh token for new tokens", description = """
            The refresh token is single use: it is revoked and a new one returned. Presenting it again \
            within 30 seconds (a duplicate request) is refused and changes nothing else; presenting \
            it later means it leaked, and every session of the account is revoked.""")
    @ApiResponse(responseCode = "200", description = "New tokens")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTH_INVALID_REFRESH_TOKEN (unknown, expired or reused)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "TENANT_NOT_ACTIVE (with tenantStatus): the tenant was suspended or closed; the refresh token stays usable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED; wait Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TokenResponse> refresh(RefreshTokenRequest request, @Parameter(hidden = true) HttpServletRequest http);

    @Operation(summary = "Switch to another of your tenants", description = """
            Like a refresh (the refresh token is rotated), but the new tokens are for tenantId, one of your \
            own memberships. A tenant you don't belong to is 404, one that isn't ACTIVE 403; either way the \
            refresh token stays usable.""")
    @ApiResponse(responseCode = "200", description = "Tokens for the other tenant")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTH_INVALID_REFRESH_TOKEN (unknown, expired or reused)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "TENANT_NOT_ACTIVE (with tenantStatus)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "MEMBERSHIP_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED; wait Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TokenResponse> switchTenant(SwitchTenantRequest request, @Parameter(hidden = true) HttpServletRequest http);

    @Operation(summary = "Sign out", description = """
            Revokes the refresh token. Always succeeds, also for an unknown token. The access token \
            stays valid until it expires (at most 15 minutes).""")
    @ApiResponse(responseCode = "204", description = "Signed out")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED; wait Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void logout(RefreshTokenRequest request, @Parameter(hidden = true) HttpServletRequest http);
}
