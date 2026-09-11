package com.funchole.backend.artifact;

import java.net.URI;
import java.util.Objects;

public record S3ArtifactStoreConfig(
        URI endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        String region,
        boolean pathStyleAccess
) {
    public S3ArtifactStoreConfig {
        Objects.requireNonNull(endpoint, "endpoint is required");
        requireText(bucket, "bucket");
        requireText(accessKey, "accessKey");
        requireText(secretKey, "secretKey");
        region = region == null || region.isBlank() ? "us-east-1" : region;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
