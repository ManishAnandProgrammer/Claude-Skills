package com.example.app.user.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.response.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

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
}
