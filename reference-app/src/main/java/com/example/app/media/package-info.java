/**
 * Files people upload and attach to a record (the bootstrap piece {@code media}), stored in S3 or an
 * S3-compatible store.
 *
 * <ul>
 *   <li><b>The bytes never pass through the application:</b> {@code POST /upload-urls} records a
 *       {@code PENDING} file and signs a PUT with the file's exact size, type and
 *       {@code Content-Disposition}; the browser uploads straight to the store.</li>
 *   <li><b>A purpose is code, its limits are data:</b> {@code api.UploadPurpose} names who may upload
 *       for it; {@code upload_policies} holds its sizes, file counts and types, changed by an admin.</li>
 *   <li><b>The owning record attaches:</b> its own write checks its rules and the file count, then
 *       {@code api.Uploads#claim} turns the files {@code IN_REVIEW} in one statement, in its transaction.
 *       Removing a file is {@code Uploads#release}.</li>
 *   <li><b>Jobs talk to the store:</b> {@code MediaVerificationJob} confirms attached files once their
 *       upload URL expired (UPLOADED or MISSING); {@code MediaCleanupJob} deletes released and
 *       never-attached files. No request waits on the store; no S3 call runs in a transaction.</li>
 * </ul>
 *
 * The rules and their reasons: house-review {@code references/media.md} (MEDIA-01…14).
 */
package com.example.app.media;
