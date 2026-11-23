package com.example.app.user.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.response.PlatformStaffResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

@Tag(name = "Platform: staff", description = """
        Platform staff deactivating a staff member who leaves. Any active staff member may; staff hold no \
        permissions among themselves. A staff member is never deleted (their audit history names them), and only \
        an operator reactivates one.""")
public interface PlatformStaffApi {

    // session-version:begin
    @Operation(summary = "Deactivate a staff member", description = """
            Their login is refused from then on and their platform tokens from their next request. Every \
            impersonation they started within an impersonation token's lifetime ends at once, and so do the access \
            tokens of the member they acted as in that tenant (401 SESSION_VERSION_STALE: the member refreshes). \
            Deactivating someone already deactivated changes nothing and answers the same. Never your own account.""")
    // session-version:else
    // @Operation(summary = "Deactivate a staff member", description = """
    //         Their login is refused from then on and their platform tokens from their next request. An \
    //         impersonation token they already hold works on until it expires: it issues nothing, and a new \
    //         impersonation is refused. Deactivating someone already deactivated changes nothing and answers the \
    //         same. Never your own account.""")
    // session-version:end
    @ApiResponse(responseCode = "200", description = "The staff member, deactivated")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID (a malformed id)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no platform token, or a deactivated staff member's)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "PLATFORM_STAFF_OWN_DEACTIVATION (your own id)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "PLATFORM_STAFF_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<PlatformStaffResponse> deactivate(@Parameter(description = "The staff member's id") UUID id);
}
