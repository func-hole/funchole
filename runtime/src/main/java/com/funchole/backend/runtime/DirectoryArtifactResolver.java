package com.funchole.backend.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Local, directory-convention-based {@link ArtifactResolver} for
 * development/tests: {@code <artifactsRoot>/<componentVersionId>/index.mjs}.
 *
 * The lookup key is exclusively componentVersionId - there is no directory
 * scanning, no "latest" symlink convention, and no filename-based version
 * inference. An unmapped componentVersionId simply resolves to nothing.
 */
public final class DirectoryArtifactResolver implements ArtifactResolver {

    private static final String ENTRY_POINT_FILE_NAME = "index.mjs";

    private final Path artifactsRoot;
    private final String runtimeType;

    public DirectoryArtifactResolver(Path artifactsRoot, String runtimeType) {
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
