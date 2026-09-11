package com.funchole.backend.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * First {@link ArtifactStore} implementation: the existing local directory
 * convention ({@code <artifactsRoot>/<componentVersionId>/index.mjs}).
 *
 * The lookup key is exclusively componentVersionId - no directory scanning,
 * no "latest" symlink convention, no filename-based version inference. An
 * unmapped componentVersionId simply resolves to nothing.
 */
public final class LocalArtifactStore implements ArtifactStore {

    private static final String ENTRY_POINT_FILE_NAME = "index.mjs";

    private final Path artifactsRoot;
    private final String runtimeType;

    public LocalArtifactStore(Path artifactsRoot, String runtimeType) {
        this.artifactsRoot = artifactsRoot;
        this.runtimeType = runtimeType;
    }

    @Override
    public Optional<ArtifactReference> resolve(UUID componentId, UUID componentVersionId) {
        if (componentId == null || componentVersionId == null) {
            return Optional.empty();
        }
        Path artifactPath = artifactsRoot.resolve(componentVersionId.toString()).resolve(ENTRY_POINT_FILE_NAME);
        if (!Files.isRegularFile(artifactPath)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactReference(componentId, componentVersionId, runtimeType, artifactPath.toAbsolutePath()));
    }
}
