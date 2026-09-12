package com.funchole.backend.controlplane.functionbuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/**
 * The local build output of a {@link RuntimeBuilder}, ready to be handed to
 * {@code artifact.ArtifactPublisher}. Runtime-neutral, like
 * {@link BuildWorkspace}: it carries only the directory a build produced
 * plus the metadata the publish step needs, never a runtime-specific type.
 *
 * <p>Ownership is independent of the {@link BuildWorkspace} it was built
 * from - a {@link RuntimeBuilder} materializes its own artifact directory
 * rather than reusing the workspace's, so closing one never affects the
 * other. The caller owns this directory from the moment {@link RuntimeBuilder#build}
 * returns it and must {@link #close()} it when done - use try-with-resources.
 */
public final class PreparedArtifact implements AutoCloseable {

    private final UUID functionVersionId;
    private final Path artifactDirectory;
    private final String entrypoint;
    private final String runtimeType;
    private final String runtimeVersion;

    public PreparedArtifact(
            UUID functionVersionId,
            Path artifactDirectory,
            String entrypoint,
            String runtimeType,
            String runtimeVersion
    ) {
        this.functionVersionId = functionVersionId;
        this.artifactDirectory = artifactDirectory;
        this.entrypoint = entrypoint;
        this.runtimeType = runtimeType;
        this.runtimeVersion = runtimeVersion;
    }

    public UUID functionVersionId() {
        return functionVersionId;
    }

    public Path artifactDirectory() {
        return artifactDirectory;
    }

    public String entrypoint() {
        return entrypoint;
    }

    public String runtimeType() {
        return runtimeType;
    }

    public String runtimeVersion() {
        return runtimeVersion;
    }

    @Override
    public void close() {
        deleteRecursively(artifactDirectory);
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                    // Temporary prepared-artifact cleanup is best-effort.
                }
            });
        } catch (IOException ignored) {
            // Temporary prepared-artifact cleanup is best-effort.
        }
    }
}
