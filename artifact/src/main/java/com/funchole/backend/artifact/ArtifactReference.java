package com.funchole.backend.artifact;

import java.nio.file.Path;
import java.util.UUID;

/**
 * The exact executable artifact pinned to one componentId/componentVersionId
 * pair. This is local-filesystem-only for this milestone - no remote
 * storage, checksums, manifests, or cache metadata.
 */
public record ArtifactReference(
        UUID componentId,
        UUID componentVersionId,
        String runtimeType,
        Path artifactPath
) {
}
