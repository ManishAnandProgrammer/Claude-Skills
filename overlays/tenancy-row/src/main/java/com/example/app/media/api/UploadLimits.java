package com.example.app.media.api;

import java.util.List;

public record UploadLimits(long minSizeBytes, long maxSizeBytes, int minFiles, int maxFiles, List<String> contentTypes) {

    public UploadLimits {
        contentTypes = List.copyOf(contentTypes);
    }
}
