package com.example.app.media;

import com.example.app.support.IntegrationTest;
import com.example.app.support.MediaStorageContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the signature enforces, against a real S3-compatible store: the PUT must carry exactly the
 * declared size, type and disposition, or the store refuses it with 403 and stores nothing.
 */
@ResourceLock(IntegrationTest.UPLOAD_POLICIES_LOCK)
class PresignedUploadIntegrationTest extends IntegrationTest {

    private static final int SIZE = 10;

    @Autowired
    private MediaStorageContainer storage;

    @Value("${app.media.bucket}")
    private String bucket;

    private UploadPolicyLimits saved;

    /** The project's first purpose, taking the PNGs these tests upload; skipped while there is none. */
    @BeforeEach
    void setThePolicy() {
        assumeAnUploadPurpose();
        saved = uploadPolicyOf(TESTED_PURPOSE);
        setUploadPolicy(TESTED_PURPOSE, 1, 10_485_760, 0, 5);
    }

    @AfterEach
    void restoreThePolicy() {
        restoreUploadPolicy(saved);
    }

    private HeadObjectResponse head(String key) {
        try (S3Client s3 = storage.client()) {
            return s3.headObject(request -> request.bucket(bucket).key(key));
        }
    }

    private static Map<String, String> with(Map<String, String> headers, String name, String value) {
        Map<String, String> changed = new LinkedHashMap<>(headers);
        if (Objects.isNull(value)) {
            changed.remove(name);
        } else {
            changed.put(name, value);
        }
        return changed;
    }

    @Test
    @DisplayName("A PUT of the signed size with the given headers is stored with its length, type and disposition")
    void signedPutIsStored() throws Exception {
        UploadUrl upload = uploadUrl(newUploader(), TESTED_PURPOSE, "scan.png", "image/png", SIZE);

        assertThat(putToStorage(upload.url(), upload.headers(), new byte[SIZE])).isEqualTo(200);

        HeadObjectResponse stored = head(upload.uploadKey());
        assertThat(stored.contentLength()).isEqualTo(SIZE);
        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(stored.contentDisposition()).isEqualTo(upload.headers().get(HttpHeaders.CONTENT_DISPOSITION));
    }

    static Stream<Arguments> tamperedPuts() {
        return Stream.of(
                Arguments.of("one byte more", SIZE + 1, HttpHeaders.CONTENT_TYPE, "image/png"),
                Arguments.of("one byte less", SIZE - 1, HttpHeaders.CONTENT_TYPE, "image/png"),
                Arguments.of("empty", 0, HttpHeaders.CONTENT_TYPE, "image/png"),
                // Far past the signed length, yet small enough that the store reads it all before refusing: with a
                // larger body it may close the connection while the client is still sending, and no answer arrives.
                Arguments.of("much more", 100_000, HttpHeaders.CONTENT_TYPE, "image/png"),
                Arguments.of("another type", SIZE, HttpHeaders.CONTENT_TYPE, "text/html"),
                Arguments.of("no disposition", SIZE, HttpHeaders.CONTENT_DISPOSITION, null),
                Arguments.of("another disposition", SIZE, HttpHeaders.CONTENT_DISPOSITION, "inline"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tamperedPuts")
    @DisplayName("A PUT of another size, type or disposition is refused 403 and stores nothing")
    void tamperedPutIsRefused(String name, int bodySize, String header, String value) throws Exception {
        UploadUrl upload = uploadUrl(newUploader(), TESTED_PURPOSE, "scan.png", "image/png", SIZE);

        assertThat(putToStorage(upload.url(), with(upload.headers(), header, value), new byte[bodySize])).isEqualTo(403);

        assertThatThrownBy(() -> head(upload.uploadKey())).isInstanceOf(NoSuchKeyException.class);
    }
}
