package com.funchole.backend.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only S3-compatible artifact store. It never resolves "latest" and
 * never falls back to another version: the object key is derived only from
 * the pinned componentVersionId.
 *
 * This store handles exact remote retrieval only - no caching, no
 * deduplication of concurrent resolves for the same version. Callers that
 * need either of those (e.g. the runtime execution module's local artifact
 * cache) compose on top of this store via the {@link ArtifactStore}
 * contract; this class does not know they exist.
 *
 * Ownership note: each successful {@link #resolve} downloads and extracts
 * into a fresh temporary directory that this store does <em>not</em> delete
 * - the caller receives it via the returned {@link ArtifactReference} and
 * owns its lifecycle (read it, copy out of it, then remove it). A store used
 * directly with no caller-side cleanup will accumulate temporary
 * directories; the intended usage is behind a layer that materializes the
 * result somewhere stable and then removes this temporary copy.
 */
public final class S3ArtifactStore implements ArtifactStore {

    private static final String ARTIFACT_FILE_NAME = "artifact.tar.gz";
    private static final String ENTRY_POINT_FILE_NAME = "index.mjs";

    private final String runtimeType;
    private final S3ArtifactClient s3Client;

    public S3ArtifactStore(String runtimeType, S3ArtifactStoreConfig config) {
        this(runtimeType, AwsS3ArtifactClient.from(config));
    }

    S3ArtifactStore(String runtimeType, S3ArtifactClient s3Client) {
        this.runtimeType = runtimeType;
        this.s3Client = s3Client;
    }

    @Override
    public Optional<ArtifactReference> resolve(UUID componentId, UUID componentVersionId) {
        if (componentId == null || componentVersionId == null) {
            return Optional.empty();
        }

        Path downloadWorkspace = createTempDirectory(componentVersionId, "download");
        try {
            Path archive = downloadWorkspace.resolve(ARTIFACT_FILE_NAME);
            if (!s3Client.download(objectKey(componentVersionId), archive)) {
                return Optional.empty();
            }

            Path extracted = createTempDirectory(componentVersionId, "extracted");
            ArtifactArchiveExtractor.extractTarGz(archive, extracted);
            return Optional.of(new ArtifactReference(
                    componentId, componentVersionId, runtimeType, extracted.resolve(ENTRY_POINT_FILE_NAME)));
        } finally {
            // Only the raw downloaded archive is this store's own concern to
            // clean up; the extracted directory above is handed to the
            // caller and deliberately left alone.
            deleteRecursively(downloadWorkspace);
        }
    }

    public static String objectKey(UUID componentVersionId) {
        if (componentVersionId == null) {
            throw new IllegalArgumentException("componentVersionId is required");
        }
        return "artifacts/" + componentVersionId + "/" + ARTIFACT_FILE_NAME;
    }

    private Path createTempDirectory(UUID componentVersionId, String purpose) {
        try {
            return Files.createTempDirectory("funchole-artifact-" + componentVersionId + "-" + purpose + "-");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create artifact " + purpose + " workspace", exception);
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
