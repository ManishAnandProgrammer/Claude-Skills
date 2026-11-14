package com.example.app.media.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * The S3 client (the jobs' HEAD and DELETE) and the presigner (upload and read URLs), from
 * {@code app.media.*}. Credentials: the SDK's default provider chain (an IAM role in production,
 * environment or system properties elsewhere), never keys in configuration.
 */
@Configuration(proxyBeanMethods = false)
class MediaStorageConfig {

    /**
     * Refreshed in the background before they expire, so signing (which runs inside requests, a read
     * URL even inside a transaction) never waits on the credentials endpoint once they are loaded.
     */
    @Bean(destroyMethod = "close")
    DefaultCredentialsProvider mediaCredentials() {
        return DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build();
    }

    @Bean(destroyMethod = "close")
    S3Client mediaS3Client(MediaProperties properties, AwsCredentialsProvider mediaCredentials) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(mediaCredentials)
                .forcePathStyle(properties.pathStyleAccess())
                .overrideConfiguration(override -> override
                        .apiCallTimeout(properties.client().apiCallTimeout())
                        .apiCallAttemptTimeout(properties.client().apiCallAttemptTimeout()));
        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner mediaS3Presigner(MediaProperties properties, AwsCredentialsProvider mediaCredentials) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(mediaCredentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleAccess()).build());
        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }
}
