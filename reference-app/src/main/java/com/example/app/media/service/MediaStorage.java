package com.example.app.media.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.media.dto.internal.PresignedUpload;
import com.example.app.media.dto.request.UploadFileRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * The object store: upload URLs, and the jobs' HEAD and DELETE. Every failure is counted
 * ({@code media.storage.failures}, by operation).
 */
@Component
class MediaStorage {

    private static final String FAILURES = "media.storage.failures";
    private static final String OPERATION = "operation";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final MediaProperties properties;
    private final MeterRegistry meterRegistry;

    MediaStorage(S3Client s3, S3Presigner presigner, MediaProperties properties, MeterRegistry meterRegistry) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * A PUT signed with the file's exact size, type and disposition ({@code X-Amz-SignedHeaders}): the
     * store refuses any other body size, type or disposition with 403. Signing is local (an HMAC); only
     * loading the credentials can fail, which is 503 {@code MEDIA_STORAGE_UNAVAILABLE}.
     */
    PresignedUpload presignUpload(String key, UploadFileRequest file) {
        String disposition = contentDisposition(file.fileName());
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentLength(file.sizeBytes())
                .contentType(file.contentType())
                .contentDisposition(disposition)
                .build();
        try {
            PresignedPutObjectRequest signed = presigner.presignPutObject(request -> request
                    .signatureDuration(properties.uploadUrlExpiry())
                    .putObjectRequest(put));
            Map<String, String> headers = Map.of(HttpHeaders.CONTENT_TYPE, file.contentType(), HttpHeaders.CONTENT_DISPOSITION, disposition);
            // X-Amz-Date has whole seconds, so the URL really stops working at the whole second.
            Instant expiresAt = signed.expiration().truncatedTo(ChronoUnit.SECONDS);
            return new PresignedUpload(signed.url().toString(), headers, expiresAt);
        } catch (SdkException failure) {
            meterRegistry.counter(FAILURES, OPERATION, "presign").increment();
            throw AppException.withCause(ErrorCode.MEDIA_STORAGE_UNAVAILABLE, failure);
        }
    }

    /** Whether the object is there. A store that doesn't answer throws {@link SdkException}. */
    boolean exists(String key) {
        try {
            s3.headObject(request -> request.bucket(properties.bucket()).key(key));
            return true;
        } catch (NoSuchKeyException absent) {
            return false;
        } catch (SdkException failure) {
            meterRegistry.counter(FAILURES, OPERATION, "head").increment();
            throw failure;
        }
    }

    /** Idempotent: deleting a key that isn't there succeeds. A store that doesn't answer throws {@link SdkException}. */
    void delete(String key) {
        try {
            s3.deleteObject(request -> request.bucket(properties.bucket()).key(key));
        } catch (SdkException failure) {
            meterRegistry.counter(FAILURES, OPERATION, "delete").increment();
            throw failure;
        }
    }

    /**
     * {@code attachment} with the name in RFC 5987 form ({@code filename*=UTF-8''…}) and an ASCII
     * fallback: the header stays ASCII (browsers send it as is) and a name can't break out of it.
     * Stored on the object, so it applies however the file is served later (CloudFront too).
     */
    static String contentDisposition(String fileName) {
        return ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString();
    }
}
