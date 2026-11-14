package com.example.app.media.api;

/**
 * Where a file is in its life. Only these moves exist: PENDING to IN_REVIEW (attached, through
 * {@link Uploads#claim}); IN_REVIEW to UPLOADED or MISSING (the verification job, after the upload URL
 * expired); IN_REVIEW, UPLOADED or MISSING to RELEASED (removed from its record, {@link Uploads#release}).
 * The cleanup job deletes RELEASED files and PENDING ones past their claim window.
 */
public enum MediaFileStatus {
    /** An upload URL was issued; nothing is attached yet, and the bytes may or may not have arrived. */
    PENDING,
    /** Attached and usable; the check that the bytes arrived runs after the upload URL expired. */
    IN_REVIEW,
    /** Attached, and the object is in the store with the signed size, type and disposition. */
    UPLOADED,
    /** Attached, but nothing arrived before the upload URL expired. Final; it keeps its slot on the record. */
    MISSING,
    /** Removed from its record; the cleanup job deletes it. */
    RELEASED
}
