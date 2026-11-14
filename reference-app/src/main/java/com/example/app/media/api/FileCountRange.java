package com.example.app.media.api;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;

/**
 * How many files a record of one purpose holds: from its policy's {@code minFiles} to {@code maxFiles}.
 * Every attached file counts, MISSING ones too: only the record's own writes change its count.
 */
public record FileCountRange(int minFiles, int maxFiles) {

    /** 400 {@code UPLOAD_FILE_COUNT_OUT_OF_RANGE} unless {@code minFiles <= count <= maxFiles}. */
    public void requireWithin(int count) {
        if (count < minFiles || count > maxFiles) {
            throw AppException.of(ErrorCode.UPLOAD_FILE_COUNT_OUT_OF_RANGE, count, minFiles, maxFiles);
        }
    }

    /** The count after a change: the files it has, minus the removed ones, plus the added ones. */
    public void requireWithin(int current, int removed, int added) {
        requireWithin(current - removed + added);
    }
}
