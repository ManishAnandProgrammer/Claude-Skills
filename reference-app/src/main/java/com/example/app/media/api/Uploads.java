package com.example.app.media.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Uploaded files for the modules whose records hold them. The record's own write checks its rules and
 * {@link #fileCountRange}, then claims the new files and releases the removed ones in its transaction;
 * no call here talks to the store.
 */
public interface Uploads {

    /** The purpose's file count range, from its policy; 404 {@code UPLOAD_POLICY_NOT_FOUND} without one. */
    FileCountRange fileCountRange(UploadPurpose purpose);

    /**
     * Attaches the caller's uploads in one statement, inside the caller's transaction (which it
     * requires): PENDING to IN_REVIEW, only for files of this purpose, uploaded by the caller, never
     * claimed and not expired. Fewer files than distinct keys is 409 {@code UPLOAD_NOT_AVAILABLE}, and
     * the caller's rollback undoes the rest.
     *
     * @return the claimed files' ids, for the record to keep
     */
    List<UUID> claim(UploadPurpose purpose, Collection<String> uploadKeys);

    /** Marks the files RELEASED, inside the caller's transaction; the cleanup job deletes them later. */
    void release(Collection<UUID> mediaFileIds);

    /** The files, in creation order, each with a read URL while it may exist. */
    List<MediaFileView> describe(Collection<UUID> mediaFileIds);

    /** Whether every one of the files is UPLOADED: for a step that needs complete files (an approval). */
    boolean allUploaded(Collection<UUID> mediaFileIds);
    // tenancy-row:begin

    // /**
    //  * A purpose's starting limits in the current tenant, once: a second call for the purpose changes
    //  * nothing. Row mode only, from the owning module's {@code TenantProvisioned} listener (a changeset
    //  * can't seed a tenant's rows there).
    //  */
    // void definePolicy(UploadPurpose purpose, UploadLimits limits);
    // tenancy-row:end
}
