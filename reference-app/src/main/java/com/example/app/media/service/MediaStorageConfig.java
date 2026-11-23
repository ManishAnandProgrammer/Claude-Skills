package com.example.app.media.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.Objects;

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
                .region(properties.region())
                .credentialsProvider(mediaCredentials)
                .forcePathStyle(properties.pathStyleAccess())
                .overrideConfiguration(override -> override
                        .apiCallTimeout(properties.client().apiCallTimeout())
                        .apiCallAttemptTimeout(properties.client().apiCallAttemptTimeout()));
        if (Objects.nonNull(properties.endpoint())) {
            builder.endpointOverride(properties.endpoint());
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner mediaS3Presigner(MediaProperties properties, AwsCredentialsProvider mediaCredentials) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(properties.region())
                .credentialsProvider(mediaCredentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleAccess()).build());
        if (Objects.nonNull(properties.endpoint())) {
            builder.endpointOverride(properties.endpoint());
        }
        return builder.build();
    }
}
