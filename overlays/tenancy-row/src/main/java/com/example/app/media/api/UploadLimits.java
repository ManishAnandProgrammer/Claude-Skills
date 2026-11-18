package com.example.app.media.api;

import java.util.List;

/**
 * A purpose's starting limits in a new tenant (row mode): what its seed row holds in the single-tenant
 * and schema modes, where a changeset inserts it. Admins change them later like any policy.
 */
public record UploadLimits(long minSizeBytes, long maxSizeBytes, int minFiles, int maxFiles, List<String> contentTypes) {

    public UploadLimits {
        contentTypes = List.copyOf(contentTypes);
    }
}
