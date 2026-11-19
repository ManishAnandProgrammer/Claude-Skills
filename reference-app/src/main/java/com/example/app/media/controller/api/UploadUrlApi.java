package com.example.app.media.controller.api;

import com.example.app.common.wrapper.ListWrapper;
import com.example.app.media.dto.request.UploadUrlRequest;
import com.example.app.media.dto.response.UploadUrlResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;

@Tag(name = "Uploads", description = """
        Files go straight from the browser to storage: ask for upload URLs here, PUT each file to its URL \
        with exactly the headers given, then send the uploadKeys in the owning record's own create or \
        update, which attaches them. A file not attached in time is deleted.""")
public interface UploadUrlApi {

    @Operation(summary = "Upload URLs for files of one purpose", description = """
            Checks that you may upload for the purpose, that the request holds at most its maxFiles, and \
            each file's exact type and size against its policy (GET /upload-policies). Each URL accepts one \
            PUT of exactly the declared size, type and disposition until expiresAt; anything else is refused \
            by storage with 403.""")
    @ApiResponse(responseCode = "201", description = "One upload per file, in the request's order")
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_FAILED (see errors); REQUEST_UNREADABLE (an unknown purpose); \
            UPLOAD_FILE_COUNT_OUT_OF_RANGE (more files than the purpose's maxFiles); UPLOAD_TYPE_NOT_ALLOWED \
            (a type the purpose doesn't accept, never SVG, HTML or XML); UPLOAD_SIZE_OUT_OF_RANGE""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (you hold none of the purpose's permissions)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "UPLOAD_POLICY_NOT_FOUND (the purpose has no policy set up)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = """
            MEDIA_STORAGE_UNAVAILABLE, DATABASE_UNAVAILABLE, DATABASE_CONTENTION, QUERY_TIMEOUT, SERVER_BUSY; \
            retry after Retry-After""",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ListWrapper<UploadUrlResponse> issue(UploadUrlRequest request);
}
