package com.funchole.backend.runtime;

import java.nio.file.Path;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

final class AwsS3ArtifactClient implements S3ArtifactClient {

    private final S3Client s3Client;
    private final String bucket;

    private AwsS3ArtifactClient(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    static AwsS3ArtifactClient from(S3ArtifactStoreConfig config) {
        S3Client client = S3Client.builder()
                .endpointOverride(config.endpoint())
                .region(Region.of(config.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.pathStyleAccess())
                        .build())
                .build();
        return new AwsS3ArtifactClient(client, config.bucket());
    }

    @Override
    public boolean download(String key, Path destination) {
        try {
            s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build(),
                    ResponseTransformer.toFile(destination)
            );
            return true;
        } catch (NoSuchKeyException exception) {
            return false;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw new IllegalStateException("Failed to download artifact from S3 key " + key, exception);
        }
    }

    @Override
    public void upload(String key, Path source) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("application/gzip")
                            .build(),
                    source
            );
        } catch (S3Exception exception) {
            throw new IllegalStateException("Failed to upload artifact to S3 key " + key, exception);
        }
    }
}
