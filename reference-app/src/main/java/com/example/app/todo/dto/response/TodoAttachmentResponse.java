package com.example.app.todo.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** One of a todo's files, mapped from the media module's view: the todo's API owns its shape. */
@Schema(description = "A file attached to the todo")
public record TodoAttachmentResponse(
        @Schema(description = "The file's id: send it in removeAttachmentIds to remove it", example = "0190a9c2-9f00-7a3c-8b4d-3e5f7a9b1c2d")
        UUID id,
        @Schema(description = "The name it was uploaded with", example = "receipt.pdf")
        String fileName,
        @Schema(description = "Its content type", example = "application/pdf")
        String contentType,
        @Schema(description = "Its size in bytes", example = "48213")
        long sizeBytes,
        @Schema(description = """
                IN_REVIEW (usable; the check that it arrived runs after its upload URL expired), UPLOADED \\
                (confirmed) or MISSING (never arrived: remove it and attach a new upload)""",
                allowableValues = {"IN_REVIEW", "UPLOADED", "MISSING"}, example = "UPLOADED")
        String status,
        @Schema(description = """
                A short-lived URL that downloads it (as an attachment, with its name); null while MISSING. \\
                Read it again from the todo rather than storing it.""")
        String url) {
}
