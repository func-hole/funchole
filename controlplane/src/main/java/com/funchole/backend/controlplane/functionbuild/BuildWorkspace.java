package com.funchole.backend.controlplane.functionbuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/**
 * A FunctionVersion's submitted source, materialized onto local disk under
 * an isolated temporary directory, ready for a {@link RuntimeBuilder} to act
 * on. Runtime-neutral by design: it carries only the information any runtime
 * needs to start a build (workspace root, entrypoint, runtime type/version) -
 * no Node/Python/Go/Rust-specific concept belongs here; that lives entirely
 * behind the {@link RuntimeBuilder} the workspace is handed to.
 *
 * <p>The caller owns this directory from the moment
 * {@link BuildWorkspaceService#prepareWorkspace} returns it and must
 * {@link #close()} it when done - use try-with-resources, mirroring
 * {@code artifact.RemoteArtifact}'s temporary-directory ownership contract.
 */
public final class BuildWorkspace implements AutoCloseable {

    private final UUID functionVersionId;
    private final Path root;
    private final String entrypoint;
    private final String runtimeType;
    private final String runtimeVersion;

    public BuildWorkspace(
            UUID functionVersionId,
            Path root,
            String entrypoint,
            String runtimeType,
            String runtimeVersion
    ) {
        this.functionVersionId = functionVersionId;
        this.root = root;
        this.entrypoint = entrypoint;
        this.runtimeType = runtimeType;
        this.runtimeVersion = runtimeVersion;
    }

    public UUID functionVersionId() {
        return functionVersionId;
    }

    public Path root() {
        return root;
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

    /**
     * Absolute path to the entrypoint file inside the workspace.
     */
    public Path entrypointPath() {
        return root.resolve(entrypoint);
    }

    @Override
    public void close() {
        deleteRecursively(root);
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
                    // Temporary build workspace cleanup is best-effort.
                }
            });
        } catch (IOException ignored) {
            // Temporary build workspace cleanup is best-effort.
        }
    }
}
