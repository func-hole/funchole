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
 *
 * <p>Once {@link ArtifactPublisher#publish} has returned successfully, the
 * remote artifact exists whether or not everything after it succeeds. Any
 * failure past that point (the version-id validation below, metadata
 * persistence, or the READY transition itself) therefore triggers a
 * best-effort {@link ArtifactPublisher#delete} of that same artifact, so a
 * failed deployment does not silently leave an orphaned, untracked object
 * behind when cleanup is possible. A failure in {@code publish} itself is
 * never compensated - there is no {@code PublishedArtifact} to reference,
 * and the existing orphan-on-write-failure gap documented for
 * {@code attachPublishedArtifact} remains an accepted limitation.
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

        // Assigned only once publish() has actually returned a value - the
        // signal that a remote artifact now exists and, if anything later
        // fails, needs to be compensated for.
        PublishedArtifact published = null;
        try {
            try (BuildWorkspace workspace = buildWorkspaceService.prepareWorkspace(functionVersionId)) {
                RuntimeBuilder runtimeBuilder = runtimeBuilderRegistry.resolve(functionVersion.getRuntime());

                try (PreparedArtifact preparedArtifact = runtimeBuilder.build(workspace)) {
                    published = artifactPublisher.publish(functionVersionId, preparedArtifact.artifactDirectory());

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
            if (published != null) {
                try {
                    artifactPublisher.delete(functionVersionId, published.objectKey());
                } catch (RuntimeException deleteException) {
                    // Same pattern as markFailed below: the original deployment
                    // failure is what the caller needs to see, not a failure to
                    // clean up after it.
                    exception.addSuppressed(deleteException);
                }
            }
            try {
                lifecycleRegistry.markFailed(functionVersionId);
            } catch (RuntimeException markFailedException) {
                exception.addSuppressed(markFailedException);
            }
            throw exception;
        }
    }
}
