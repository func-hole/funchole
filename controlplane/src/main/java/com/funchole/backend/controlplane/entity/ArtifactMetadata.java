package com.funchole.backend.controlplane.entity;

import java.time.OffsetDateTime;

public record ArtifactMetadata(
        String objectKey,
        String format,
        String sha256,
        long sizeBytes,
        OffsetDateTime publishedAt
) {
}
