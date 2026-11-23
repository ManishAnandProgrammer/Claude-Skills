package com.example.app.user.controller.api;

import com.example.app.common.wrapper.BoundedListWrapper;
import com.example.app.user.dto.response.MembershipResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

@Tag(name = "Users", description = "The signed-in user's account")
public interface MembershipApi {

    // roles:begin
    @Operation(summary = "The signed-in user's tenants", description = """
            Every tenant the user belongs to, with the tenant's status, most recently used first; current marks \
            the token's own. POST /auth/switch-tenant takes one of these ids; what the user may do there is \
            GET /users/me/access, signed in to it.""")
    // roles:else
    // @Operation(summary = "The signed-in user's tenants", description = """
    //         Every tenant the user belongs to, with their role there and the tenant's status, most recently \
    //         used first; current marks the token's own. POST /auth/switch-tenant takes one of these ids.""")
    // roles:end
    @ApiResponse(responseCode = "200", description = "The user's tenants")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED (no or invalid token)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    BoundedListWrapper<MembershipResponse> mine();
}
