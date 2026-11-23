package com.example.app.media.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface Uploads {

    FileCountRange fileCountRange(UploadPurpose purpose);

    /**
     * Inside the caller's transaction, which it requires: fewer files than distinct keys is 409
     * {@code UPLOAD_NOT_AVAILABLE}, and the caller's rollback undoes the rest.
     */
    List<UUID> claim(UploadPurpose purpose, Collection<String> uploadKeys);

    void release(Collection<UUID> mediaFileIds);

    List<MediaFileView> describe(Collection<UUID> mediaFileIds);

    boolean allVerified(Collection<UUID> mediaFileIds);
    // tenancy-row:begin

    // void definePolicyIfAbsent(UploadPurpose purpose, UploadLimits limits);
    // tenancy-row:end
}
