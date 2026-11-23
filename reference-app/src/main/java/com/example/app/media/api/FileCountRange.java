package com.example.app.media.api;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;

public record FileCountRange(int minFiles, int maxFiles) {

    public void requireWithin(int count) {
        if (count < minFiles || count > maxFiles) {
            throw AppException.of(ErrorCode.UPLOAD_FILE_COUNT_OUT_OF_RANGE, count, minFiles, maxFiles);
        }
    }

    public void requireWithin(int current, int removed, int added) {
        requireWithin(current - removed + added);
    }
}
