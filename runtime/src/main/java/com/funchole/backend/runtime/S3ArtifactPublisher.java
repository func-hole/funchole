package com.funchole.backend.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class S3ArtifactPublisher implements ArtifactPublisher {

    private static final String ARTIFACT_FILE_NAME = "artifact.tar.gz";

    private final S3ArtifactClient s3Client;

    public S3ArtifactPublisher(S3ArtifactStoreConfig config) {
        this(AwsS3ArtifactClient.from(config));
    }

    S3ArtifactPublisher(S3ArtifactClient s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public PublishedArtifact publish(UUID componentVersionId, Path preparedArtifactDirectory) {
        if (componentVersionId == null) {
            throw new IllegalArgumentException("componentVersionId is required");
        }

        Path workspace = createTempDirectory(componentVersionId);
        try {
            String objectKey = S3ArtifactStore.objectKey(componentVersionId);
            Path archive = workspace.resolve(ARTIFACT_FILE_NAME);
            ArtifactArchivePackager.packageTarGz(preparedArtifactDirectory, archive);
            upload(objectKey, archive);
            return new PublishedArtifact(componentVersionId, objectKey);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private void upload(String objectKey, Path archive) {
        try {
            s3Client.upload(objectKey, archive);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to publish artifact to S3 key " + objectKey, exception);
        }
    }

    private Path createTempDirectory(UUID componentVersionId) {
        try {
            return Files.createTempDirectory("funchole-artifact-publish-" + componentVersionId + "-");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create artifact publish workspace", exception);
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
                    // Temporary publish workspace cleanup is best-effort.
                }
            });
        } catch (IOException ignored) {
            // Temporary publish workspace cleanup is best-effort.
        }
    }
}
