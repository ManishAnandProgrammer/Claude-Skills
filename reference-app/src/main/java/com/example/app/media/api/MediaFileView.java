package com.example.app.media.api;

import java.util.UUID;

/**
 * A file attached to a record, as the owning module reads it ({@link Uploads#describe}). A module
 * contract, never a response body: the owning module maps it into its own response record, so a
 * change here never reshapes another module's API.
 *
 * @param id          the file's id, which the owner's update takes to remove it
 * @param fileName    the name it was uploaded with
 * @param contentType its content type
 * @param sizeBytes   its size in bytes
 * @param status      IN_REVIEW, UPLOADED or MISSING while attached
 * @param url         a short-lived read URL; null while MISSING
 */
public record MediaFileView(UUID id, String fileName, String contentType, long sizeBytes, MediaFileStatus status, String url) {
}
