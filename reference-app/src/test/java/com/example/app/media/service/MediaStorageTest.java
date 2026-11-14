package com.example.app.media.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.media.dto.request.UploadFileRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaStorageTest {

    private static final MediaProperties PROPERTIES = new MediaProperties("bucket", "us-east-1", "", false,
            Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofHours(1),
            new MediaProperties.Client(Duration.ofSeconds(10), Duration.ofSeconds(3)));

    @Test
    @DisplayName("Credentials that can't be loaded answer MEDIA_STORAGE_UNAVAILABLE (a 503) and are counted")
    void unloadableCredentialsAreStorageUnavailable() {
        AwsCredentialsProvider unreachable = () -> {
            throw SdkClientException.create("Unable to load credentials from any of the providers in the chain");
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try (S3Presigner presigner = S3Presigner.builder().region(Region.US_EAST_1).credentialsProvider(unreachable).build();
             S3Client s3 = S3Client.builder().region(Region.US_EAST_1).credentialsProvider(unreachable).build()) {
            MediaStorage storage = new MediaStorage(s3, presigner, PROPERTIES, meters);

            assertThatThrownBy(() -> storage.presignUpload("TEST/key", new UploadFileRequest("a.pdf", "application/pdf", 10L)))
                    .isInstanceOfSatisfying(AppException.class,
                            refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.MEDIA_STORAGE_UNAVAILABLE));
        }
        assertThat(meters.counter("media.storage.failures", "operation", "presign").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("The disposition is an ASCII attachment with the name in RFC 5987 form; a line break can't escape it")
    void dispositionIsAsciiAndCantBreakOut() {
        String disposition = MediaStorage.contentDisposition("Rechnung März\r\nX-Evil: 1.pdf");

        assertThat(disposition).startsWith("attachment;").contains("filename*=UTF-8''Rechnung%20M%C3%A4rz");
        assertThat(disposition).doesNotContain("\r", "\n").matches("\\p{ASCII}+");
    }
}
