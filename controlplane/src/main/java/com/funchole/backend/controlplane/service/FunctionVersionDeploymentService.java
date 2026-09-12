package com.funchole.backend.controlplane.service;

import com.funchole.backend.artifact.ArtifactPublisher;
import com.funchole.backend.artifact.PublishedArtifact;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.functionbuild.BuildWorkspace;
import com.funchole.backend.controlplane.functionbuild.BuildWorkspaceService;
import com.funchole.backend.controlplane.functionbuild.PreparedArtifact;
import com.funchole.backend.controlplane.functionbuild.RuntimeBuilder;
import com.funchole.backend.controlplane.functionbuild.RuntimeBuilderRegistry;
import java.util.UUID;

/**
 * Orchestrates the full source-to-deployed-artifact pipeline for a
 * FunctionVersion and drives its deployment lifecycle:
 *
 * <pre>
 * DRAFT -&gt; PUBLISHING -&gt; BuildWorkspaceService.prepareWorkspace(...)
 *                     -&gt; RuntimeBuilderRegistry.resolve(...) -&gt; RuntimeBuilder.build(...)
 *                     -&gt; ArtifactPublisher.publish(...)
 *                     -&gt; FunctionVersionArtifactRegistry.attachPublishedArtifact(...)
 *                     -&gt; READY
 *                     \-&gt; (any failure above) -&gt;----------------------------------&gt; FAILED
 * </pre>
 *
 * This class holds no build logic itself - it only sequences its
 * collaborators and validates the hand-offs between them: source
 * materialization stays in {@link BuildWorkspaceService}, runtime-specific
 * building stays entirely behind {@link RuntimeBuilder} (selected via
 * {@link RuntimeBuilderRegistry} from the FunctionVersion's own declared
 * runtime - never hardcoded), packaging/checksum/upload stays in
 * {@link ArtifactPublisher}, artifact metadata persistence and immutability
 * stay in {@link FunctionVersionArtifactRegistry}, and status persistence
 * stays in {@link FunctionVersionLifecycleRegistry}.
 *
 * <p>{@link BuildWorkspace} and {@link PreparedArtifact} are both
 * {@code AutoCloseable}; nesting them in try-with-resources guarantees both
 * are cleaned up whether the build/publish sequence succeeds or fails.
 */
public class FunctionVersionDeploymentService {

    private final BuildWorkspaceService buildWorkspaceService;
    private final RuntimeBuilderRegistry runtimeBuilderRegistry;
    private final ArtifactPublisher artifactPublisher;
    private final FunctionVersionArtifactRegistry artifactRegistry;
    private final FunctionVersionLifecycleRegistry lifecycleRegistry;

    public FunctionVersionDeploymentService(
            BuildWorkspaceService buildWorkspaceService,
            RuntimeBuilderRegistry runtimeBuilderRegistry,
            ArtifactPublisher artifactPublisher,
            FunctionVersionArtifactRegistry artifactRegistry,
            FunctionVersionLifecycleRegistry lifecycleRegistry
    ) {
        this.buildWorkspaceService = buildWorkspaceService;
        this.runtimeBuilderRegistry = runtimeBuilderRegistry;
        this.artifactPublisher = artifactPublisher;
        this.artifactRegistry = artifactRegistry;
        this.lifecycleRegistry = lifecycleRegistry;
    }

    public FunctionVersion deploy(UUID functionVersionId) {
        // Checked before the version ever enters PUBLISHING, so an
        // already-published version is rejected without touching status,
        // build, or publish at all.
        if (artifactRegistry.findArtifactMetadata(functionVersionId).isPresent()) {
            throw new IllegalStateException(
                    "Function version already has a published artifact and cannot be republished: " + functionVersionId);
        }

        // Persisted immediately, in its own transaction, before any build or
        // publish work starts - rejects deployment when already PUBLISHING,
        // READY, or FAILED (FAILED stays terminal: no retry path yet).
        FunctionVersion functionVersion = lifecycleRegistry.beginPublishing(functionVersionId);

        try {
            try (BuildWorkspace workspace = buildWorkspaceService.prepareWorkspace(functionVersionId)) {
                RuntimeBuilder runtimeBuilder = runtimeBuilderRegistry.resolve(functionVersion.getRuntime());

                try (PreparedArtifact preparedArtifact = runtimeBuilder.build(workspace)) {
                    PublishedArtifact published =
                            artifactPublisher.publish(functionVersionId, preparedArtifact.artifactDirectory());

                    if (!functionVersionId.equals(published.componentVersionId())) {
                        throw new IllegalStateException(
                                "Published artifact version id " + published.componentVersionId()
                                        + " does not match requested function version id " + functionVersionId);
                    }

                    artifactRegistry.attachPublishedArtifact(
                            functionVersionId,
                            published.objectKey(),
                            FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                            published.sha256(),
                            published.sizeBytes()
                    );
                }
            }

            return lifecycleRegistry.markReady(functionVersionId);
        } catch (RuntimeException exception) {
            try {
                lifecycleRegistry.markFailed(functionVersionId);
            } catch (RuntimeException markFailedException) {
                // The original deployment failure is the one the caller needs to
                // see; a failure recording that failure is secondary information,
                // not a replacement for it.
                exception.addSuppressed(markFailedException);
            }
            throw exception;
        }
    }
}
