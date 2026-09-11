package com.funchole.backend.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
            // Computed from the packaged archive itself, so it represents the exact bytes uploaded below.
            ArchiveDigest digest = computeDigest(archive);
            upload(objectKey, archive);
            return new PublishedArtifact(componentVersionId, objectKey, digest.sha256Hex(), digest.sizeBytes());
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static ArchiveDigest computeDigest(Path archive) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
            return new ArchiveDigest(HexFormat.of().formatHex(digest.digest()), size);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to compute artifact checksum for " + archive, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }

    private record ArchiveDigest(String sha256Hex, long sizeBytes) {
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
