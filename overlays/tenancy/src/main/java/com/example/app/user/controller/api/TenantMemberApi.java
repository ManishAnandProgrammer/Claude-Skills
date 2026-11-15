package com.example.app.user.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.request.TenantMemberAddRequest;
import com.example.app.user.dto.request.TenantMemberUpdateRequest;
import com.example.app.user.dto.response.TenantMemberResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "Tenant members", description = """
        Who belongs to the caller's tenant, for its ADMINs. A change takes effect at the member's next \
        login, refresh or switch: within one access-token lifetime (15 minutes). Managing members is the \
        exception: the caller must be an ADMIN at the moment of the call, so a removed or demoted ADMIN's \
        older token is refused here at once. Every tenant keeps at least one ADMIN, and nobody changes or \
        removes their own membership.""")
public interface TenantMemberApi {

    @Operation(summary = "Add an existing account to this tenant", description = """
            The person must have signed up (invitations come later); the answer tells an ADMIN whether an \
            email has an account.""")
    @ApiResponse(responseCode = "201", description = "Added")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, REQUEST_UNREADABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (not an ADMIN of this tenant now, whatever the token says)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_MEMBER_ACCOUNT_NOT_FOUND (no account with that email)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "TENANT_MEMBER_EXISTS (already a member)",
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
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (not an ADMIN of this tenant now, whatever the token says)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_MEMBER_NOT_FOUND (not a member of this tenant)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = """
            TENANT_MEMBER_LAST_ADMIN (it would leave no ADMIN), TENANT_MEMBER_OWN_CHANGE (your own \
            membership), OPTIMISTIC_LOCK_CONFLICT (a stale version: the one the add or the last change \
            answered is current, sign-ins don't move it)""",
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
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (not an ADMIN of this tenant now, whatever the token says)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TENANT_MEMBER_NOT_FOUND (not a member of this tenant)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = """
            TENANT_MEMBER_LAST_ADMIN (the only ADMIN), TENANT_MEMBER_OWN_CHANGE (your own membership)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void remove(@Parameter(description = "The member's account id") UUID userId);
}
