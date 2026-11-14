package com.example.app.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * An S3-compatible store (versitygw on a local directory) for the media tests. The application reaches
 * it through {@code app.media.endpoint} and the SDK's default credentials chain; tests use
 * {@link #client()} to look at the bucket directly.
 */
public class MediaStorageContainer extends GenericContainer<MediaStorageContainer> {

    public static final String ACCESS_KEY = "test-access-key";
    public static final String SECRET_KEY = "test-secret-key-0123456789";
    private static final int PORT = 7070;
    private static final String REGION = "us-east-1";

    public MediaStorageContainer() {
        super(DockerImageName.parse("versity/versitygw:v1.11.0"));
        withEnv("ROOT_ACCESS_KEY", ACCESS_KEY);
        withEnv("ROOT_SECRET_KEY", SECRET_KEY);
        withExposedPorts(PORT);
        // The image's entrypoint takes the backend as arguments; the posix backend needs its directory first.
        withCreateContainerCmdModifier(command -> command.withEntrypoint("sh"));
        withCommand("-c", "mkdir -p /tmp/vgw && versitygw posix /tmp/vgw");
    }

    public String endpoint() {
        return "http://" + getHost() + ":" + getMappedPort(PORT);
    }

    /** A client of its own, for fixtures and assertions; close it after use. */
    public S3Client client() {
        return S3Client.builder()
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .endpointOverride(URI.create(endpoint()))
                .forcePathStyle(true)
                .build();
    }

    public void createBucket(String bucket) {
        try (S3Client s3 = client()) {
            s3.createBucket(request -> request.bucket(bucket));
        }
    }
}
