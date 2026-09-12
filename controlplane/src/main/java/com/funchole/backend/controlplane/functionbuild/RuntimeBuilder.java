package com.funchole.backend.controlplane.functionbuild;

/**
 * Runtime-specific build contract: turns a runtime-neutral
 * {@link BuildWorkspace} into a {@link PreparedArtifact} ready for
 * {@code artifact.ArtifactPublisher}. Each runtime (Node, Python, Go, Rust,
 * ...) gets its own implementation - dependency installation, compilation,
 * and bundling all stay entirely inside {@link #build}, out of
 * {@link BuildWorkspaceService} and {@link RuntimeBuilderRegistry}.
 */
public interface RuntimeBuilder {

    /**
     * Whether this builder handles the given FunctionVersion runtime type
     * (e.g. {@code "NODE"}).
     */
    boolean supports(String runtimeType);

    /**
     * Builds the materialized source in {@code workspace} into a
     * {@link PreparedArtifact}. The workspace remains owned by its caller -
     * this method does not close it.
     */
    PreparedArtifact build(BuildWorkspace workspace);
}
