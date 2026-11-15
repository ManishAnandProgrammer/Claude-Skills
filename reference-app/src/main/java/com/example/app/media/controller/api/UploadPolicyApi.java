package com.example.app.media.controller.api;

import com.example.app.common.wrapper.ListWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.media.api.UploadPurpose;
import com.example.app.media.dto.request.UploadPolicyUpdateRequest;
import com.example.app.media.dto.response.UploadPolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

@Tag(name = "Upload policies", description = """
        Each upload purpose's limits: file sizes, files per record and content types. Read them to check \
        files before uploading; the server checks them again. Admins (role ADMIN) change them, with no \
        restart.""")
public interface UploadPolicyApi {

    @Operation(summary = "Every purpose's limits", description = """
            With canUpload: whether your role may upload for the purpose (a hint; POST /upload-urls decides).""")
    @ApiResponse(responseCode = "200", description = "Every purpose, by name")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, QUERY_TIMEOUT, SERVER_BUSY; retry after Retry-After",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ListWrapper<UploadPolicyResponse> all();

    @Operation(summary = "Replace a purpose's limits", description = """
            Send every limit and the version you last read; a stale one is refused with 409 \
            OPTIMISTIC_LOCK_CONFLICT. New uploads and record changes use the new limits at once; files \
            already attached stay, even when the range shrinks (the record's next change of its files must \
            end inside it).""")
    @ApiResponse(responseCode = "200", description = "Replaced; the version increments when something changed")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (see errors: a limit missing or out of range, minSizeBytes above maxSizeBytes, \
            minFiles above maxFiles); REQUEST_UNREADABLE; REQUEST_PARAMETER_INVALID (an unknown purpose); \
            UPLOAD_TYPE_NOT_ALLOWED (a type the application never accepts)""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (not an admin)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "UPLOAD_POLICY_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "OPTIMISTIC_LOCK_CONFLICT",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ObjectWrapper<UploadPolicyResponse> update(@Parameter(description = "The purpose") UploadPurpose purpose,
                                               UploadPolicyUpdateRequest request);
}
