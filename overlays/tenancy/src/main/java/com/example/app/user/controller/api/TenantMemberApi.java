package com.example.app.user.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.user.dto.request.TenantMemberAddRequest;
import com.example.app.user.dto.request.TenantMemberFilter;
import com.example.app.user.dto.request.TenantMemberUpdateRequest;
import com.example.app.user.dto.response.TenantMemberResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "Tenant members", description = """
        Who belongs to the caller's tenant, for who holds TENANT_MEMBERS_MANAGE there (its ADMINs). A change \
        takes effect at the member's next login, refresh or switch: within one access-token lifetime (15 \
        minutes). Managing members is the exception: the caller's role must grant it at the moment of the \
        call, so a removed or demoted ADMIN's older token is refused here at once; reading the members goes by \
        the token's permissions. Every tenant \
        keeps at least one ADMIN, and nobody changes or removes their own membership.""")
public interface TenantMemberApi {

    @Operation(summary = "A page of this tenant's members", description = """
            The caller's tenant only; removed members are not listed. Each row carries the version a role \
            change sends back. Paged with page and size (the default and the maximums come from \
            configuration; beyond them is a 400), ordered by sort (NEWEST, the default: the latest to join \
            first, or OLDEST), filtered by ids (find by account ids: unknown ids, or another tenant's \
            members, are simply absent), role and query (a case-insensitive part of the email).""")
    @ApiResponse(responseCode = "200", description = "One page, with pageInfo")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (page, size, query or the number of ids out of range); \
            REQUEST_PARAMETER_INVALID (an unknown sort or role, a malformed id, a non-numeric page or \
            size; see errors)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    PaginationWrapper<TenantMemberResponse> all(@ParameterObject TenantMemberFilter filter);

    @Operation(summary = "One member of this tenant", description = """
            With the version a role change sends back. Another tenant's member is not a member here.""")
    @ApiResponse(responseCode = "200", description = "The member")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = """
            TENANT_MEMBER_NOT_FOUND (not a member of this tenant: unknown, removed, or another tenant's)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TenantMemberResponse> get(@Parameter(description = "The member's account id") UUID userId);

    @Operation(summary = "Add an existing account to this tenant", description = """
            The person must have signed up (invitations come later); the answer tells an ADMIN whether an \
            email has an account, so adds are rate limited per ADMIN account (20 per 10 minutes by default, \
            app.rate-limit.members.add-per-account) whenever rate limiting is on (app.rate-limit.enabled); \
            with it off they are not limited.""")
    @ApiResponse(responseCode = "201", description = "Added")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (your role here doesn't grant TENANT_MEMBERS_MANAGE now, whatever the token says)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_MEMBER_ACCOUNT_NOT_FOUND (no account with that email)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TENANT_MEMBER_EXISTS (already a member)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED (this ADMIN's adds); wait Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TenantMemberResponse> add(TenantMemberAddRequest request);

    @Operation(summary = "Change a member's role in this tenant")
    @ApiResponse(responseCode = "200", description = "Changed")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = """
            ACCESS_DENIED (your role here doesn't grant TENANT_MEMBERS_MANAGE now, whatever the token says), TENANT_MEMBER_OWN_CHANGE \
            (your own membership: another ADMIN changes it)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_MEMBER_NOT_FOUND (not a member of this tenant)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = """
            TENANT_MEMBER_LAST_ADMIN (it would leave no ADMIN), OPTIMISTIC_LOCK_CONFLICT (a stale version: the list, the member's GET, the add and \
            the last change answer the current one; sign-ins don't move it)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<TenantMemberResponse> update(@Parameter(description = "The member's account id") UUID userId,
                                               TenantMemberUpdateRequest request);

    @Operation(summary = "Remove a member from this tenant", description = """
            The account stays; it just no longer belongs here. Its refresh tokens for this tenant stop \
            working at once, for good (adding the member again doesn't revive them), its access token \
            within its lifetime.""")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (a malformed id)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = """
            ACCESS_DENIED (your role here doesn't grant TENANT_MEMBERS_MANAGE now, whatever the token says), TENANT_MEMBER_OWN_CHANGE \
            (your own membership: another ADMIN removes it)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_MEMBER_NOT_FOUND (not a member of this tenant)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TENANT_MEMBER_LAST_ADMIN (the only ADMIN)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void remove(@Parameter(description = "The member's account id") UUID userId);
}
