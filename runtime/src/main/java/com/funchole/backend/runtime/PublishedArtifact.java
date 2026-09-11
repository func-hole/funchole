package com.funchole.backend.runtime;

import java.util.UUID;

public record PublishedArtifact(
        UUID componentVersionId,
        String objectKey
) {
}
