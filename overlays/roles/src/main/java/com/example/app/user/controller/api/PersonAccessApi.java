package com.example.app.user.controller.api;

import com.example.app.common.security.Permission;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.request.PersonalGrantRequest;
import com.example.app.user.dto.request.RoleAssignmentRequest;
import com.example.app.user.dto.response.PersonAccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "People's access", description = """
        A person's roles and personal grants (ACCESS_GRANT), and where each of their permissions comes from. Every \
        change applies on the person's next request. Never your own; never someone who holds a permission you don't; \
        only what you hold yourself.""")
public interface PersonAccessApi {

    String RULES_403 = """
            ACCESS_OWN_CHANGE (your own access), ACCESS_TARGET_HOLDS_MORE (they hold a permission you don't), \
            ACCESS_NOT_HELD (it gives a permission you don't hold)""";

    @Operation(summary = "A person's access, and where it comes from", description = """
            Their roles and personal grants as given (expired ones included until removed: they give nothing), what \
            they hold now with the roles or the ALLOW that give each permission, and what a DENY or a missing \
            prerequisite took away: the answer to "why can she do this".""")
    @ApiResponse(responseCode = "200", description = "Their access")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (the id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<PersonAccessResponse> access(UUID userId);

    @Operation(summary = "Give a person a role (or change its end)", description = """
            For good, or until validUntil. Only a role whose every permission you hold. Answers their access.""")
    @ApiResponse(responseCode = "200", description = "Their access, with the role")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (validUntil not in the future), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = RULES_403,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND, ROLE_NOT_FOUND, ROLE_GONE (deleted meanwhile)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "ACCESS_LAST_MANAGER",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<PersonAccessResponse> assignRole(UUID userId, UUID roleId, RoleAssignmentRequest request);

    @Operation(summary = "Take a role from a person", description = "One they don't hold changes nothing.")
    @ApiResponse(responseCode = "204", description = "Taken")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (an id is not a UUID)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_OWN_CHANGE, ACCESS_TARGET_HOLDS_MORE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "ACCESS_LAST_MANAGER",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void revokeRole(UUID userId, UUID roleId);

    @Operation(summary = "Give or take one permission (or change the grant)", description = """
            ALLOW adds it whatever the person's roles give; DENY takes it away whatever gives it (a deny always \
            wins), and with it every permission that needs it. For good, or until validUntil. Only a permission you \
            hold. Answers their access.""")
    @ApiResponse(responseCode = "200", description = "Their access, with the grant")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (see errors), REQUEST_UNREADABLE, REQUEST_PARAMETER_INVALID (an unknown permission)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = RULES_403,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "ACCESS_LAST_MANAGER",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<PersonAccessResponse> grant(UUID userId, Permission permission, PersonalGrantRequest request);

    @Operation(summary = "Remove a person's grant of one permission", description = "Only of a permission you hold; none changes nothing.")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (a malformed id, an unknown permission)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = RULES_403,
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "ACCESS_LAST_MANAGER",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    void removeGrant(UUID userId, Permission permission);
}
