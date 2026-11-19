package com.example.app.user.controller.api;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.dto.response.PermissionCatalogueResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

@Tag(name = "Permissions", description = "What can be granted: the list a role editor builds roles from.")
public interface PermissionCatalogueApi {

    @Operation(summary = "Every permission and area", description = """
            Each permission's code, area, reach and prerequisites (a role holding it must hold them too), and the \
            areas, in the enums' order: the same list for every signed-in caller, the Permission enum the API \
            document already publishes with what a role editor needs beside it. Nothing about who holds what; \
            labels, order and icons are the client's.""")
    @ApiResponse(responseCode = "200", description = "The catalogue")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY (reading your access)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<PermissionCatalogueResponse> catalogue();
}
