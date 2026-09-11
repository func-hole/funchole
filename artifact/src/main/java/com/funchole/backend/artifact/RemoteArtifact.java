package com.funchole.backend.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A remote artifact materialized into a temporary local directory by a
 * {@link RemoteArtifactStore}. The caller owns this directory from the
 * moment {@code resolve} returns it and is responsible for {@link #close()}
 * once done with it (typically: copy its contents somewhere stable, then
 * close it) - use try-with-resources so cleanup happens even if that copy
 * fails.
 */
public final class RemoteArtifact implements AutoCloseable {

    private final ArtifactReference reference;
    private final Path temporaryDirectory;

    public RemoteArtifact(ArtifactReference reference, Path temporaryDirectory) {
        this.reference = reference;
        this.temporaryDirectory = temporaryDirectory;
    }

    public ArtifactReference reference() {
        return reference;
    }

    @Override
    public void close() {
        deleteRecursively(temporaryDirectory);
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
                    // Temporary remote artifact cleanup is best-effort.
                }
            });
        } catch (IOException ignored) {
            // Temporary remote artifact cleanup is best-effort.
        }
    }
}
