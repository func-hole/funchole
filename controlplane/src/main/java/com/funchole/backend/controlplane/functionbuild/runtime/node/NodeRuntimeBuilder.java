package com.funchole.backend.controlplane.functionbuild.runtime.node;

import com.funchole.backend.controlplane.functionbuild.BuildWorkspace;
import com.funchole.backend.controlplane.functionbuild.PreparedArtifact;
import com.funchole.backend.controlplane.functionbuild.RuntimeBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Placeholder {@link RuntimeBuilder} for the {@code NODE} runtime. Copies
 * the materialized workspace as-is into its own artifact directory, with no
 * dependency installation, bundling, or compilation - those are future work.
 * This exists only so {@link com.funchole.backend.controlplane.functionbuild.RuntimeBuilderRegistry}
 * has a real {@code NODE} registration to resolve and wire against.
 */
@Component
public class NodeRuntimeBuilder implements RuntimeBuilder {

    private static final String RUNTIME_TYPE = "NODE";
    private static final String ARTIFACT_DIRECTORY_PREFIX = "funchole-prepared-";

    @Override
    public boolean supports(String runtimeType) {
        return RUNTIME_TYPE.equalsIgnoreCase(runtimeType);
    }

    @Override
    public PreparedArtifact build(BuildWorkspace workspace) {
        Path artifactDirectory = copyToNewArtifactDirectory(workspace);
        return new PreparedArtifact(
                workspace.functionVersionId(),
                artifactDirectory,
                workspace.entrypoint(),
                workspace.runtimeType(),
                workspace.runtimeVersion()
        );
    }

    private Path copyToNewArtifactDirectory(BuildWorkspace workspace) {
        try {
            Path artifactDirectory = Files.createTempDirectory(
                    ARTIFACT_DIRECTORY_PREFIX + workspace.functionVersionId() + "-");
            try (var paths = Files.walk(workspace.root())) {
                for (Path source : (Iterable<Path>) paths::iterator) {
                    Path target = artifactDirectory.resolve(workspace.root().relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target);
                    }
                }
            }
            return artifactDirectory;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to prepare Node artifact for function version: " + workspace.functionVersionId(), exception);
        }
    }
}
