package com.funchole.backend.artifact;

import java.util.UUID;

public record PublishedArtifact(
        UUID componentVersionId,
        String objectKey,
        String sha256,
        long sizeBytes
) {
}
