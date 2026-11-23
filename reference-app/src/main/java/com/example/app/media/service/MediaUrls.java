package com.example.app.media.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * No cache: signing is a local HMAC, and a URL signed twice differs anyway (the SDK can't fix the
 * signing time).
 */
@Component
@RequiredArgsConstructor
class MediaUrls {

    private final S3Presigner presigner;
    private final MediaProperties properties;

    String readUrl(String key) {
        try {
            return presigner.presignGetObject(request -> request
                    .signatureDuration(properties.readUrlExpiry())
                    .getObjectRequest(get -> get.bucket(properties.bucket()).key(key)))
                    .url().toString();
        } catch (SdkException failure) {
            throw AppException.withCause(ErrorCode.MEDIA_STORAGE_UNAVAILABLE, failure);
        }
    }
}
