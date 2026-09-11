package com.funchole.backend.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only S3-compatible artifact store. It never resolves "latest" and
 * never falls back to another version: the object key is derived only from
 * the pinned componentVersionId.
 */
public final class S3ArtifactStore implements ArtifactStore {

    private static final String ARTIFACT_FILE_NAME = "artifact.tar.gz";

    private final ArtifactCache cache;
    private final S3ArtifactClient s3Client;

    public S3ArtifactStore(ArtifactCache cache, S3ArtifactStoreConfig config) {
        this(cache, AwsS3ArtifactClient.from(config));
    }

    S3ArtifactStore(ArtifactCache cache, S3ArtifactClient s3Client) {
        this.cache = cache;
        this.s3Client = s3Client;
    }

    @Override
    public Optional<ArtifactReference> resolve(UUID componentId, UUID componentVersionId) {
        Optional<ArtifactReference> hit = cache.resolve(componentId, componentVersionId);
        if (hit.isPresent()) {
            return hit;
        }

        Path workspace = createTempDirectory(componentVersionId);
        try {
            Path archive = workspace.resolve(ARTIFACT_FILE_NAME);
            if (!s3Client.download(objectKey(componentVersionId), archive)) {
                return Optional.empty();
            }

            Path extracted = workspace.resolve("extracted");
            ArtifactArchiveExtractor.extractTarGz(archive, extracted);
            return Optional.of(cache.put(componentId, componentVersionId, extracted));
        } finally {
            deleteRecursively(workspace);
        }
    }

    public static String objectKey(UUID componentVersionId) {
        if (componentVersionId == null) {
            throw new IllegalArgumentException("componentVersionId is required");
        }
        return "artifacts/" + componentVersionId + "/" + ARTIFACT_FILE_NAME;
    }

    private Path createTempDirectory(UUID componentVersionId) {
        try {
            return Files.createTempDirectory("funchole-artifact-" + componentVersionId + "-");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create artifact download workspace", exception);
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary download workspace cleanup is best-effort.
                }
            });
        } catch (IOException ignored) {
            // Temporary download workspace cleanup is best-effort.
        }
    }
}
