package com.funchole.backend.controlplane.service;

import com.funchole.backend.artifact.ArtifactPublisher;
import com.funchole.backend.artifact.PublishedArtifact;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Orchestrates publishing a prepared artifact directory for a FunctionVersion
 * and drives its deployment lifecycle:
 *
 * <pre>
 * DRAFT -&gt; PUBLISHING -&gt; artifact publish -&gt; artifact metadata registration -&gt; READY
 *                     \-&gt; (any failure) -&gt;------------------------------------&gt; FAILED
 * </pre>
 *
 * Packaging/checksum/upload stays in {@link ArtifactPublisher}; artifact
 * metadata persistence and immutability stay in
 * {@link FunctionVersionArtifactRegistry}; status persistence stays in
 * {@link FunctionVersionLifecycleRegistry}. This class only sequences the
 * three and validates the hand-offs between them.
 */
public class FunctionVersionDeploymentService {

    private final ArtifactPublisher artifactPublisher;
    private final FunctionVersionArtifactRegistry artifactRegistry;
    private final FunctionVersionLifecycleRegistry lifecycleRegistry;

    public FunctionVersionDeploymentService(
            ArtifactPublisher artifactPublisher,
            FunctionVersionArtifactRegistry artifactRegistry,
            FunctionVersionLifecycleRegistry lifecycleRegistry
    ) {
        this.artifactPublisher = artifactPublisher;
        this.artifactRegistry = artifactRegistry;
        this.lifecycleRegistry = lifecycleRegistry;
    }

    public FunctionVersion deployArtifact(UUID functionVersionId, Path preparedArtifactDirectory) {
        // Persisted immediately, in its own transaction, before the external
        // publish call starts - rejects deployment when already PUBLISHING,
        // READY, or FAILED (FAILED stays terminal: no retry path yet).
        lifecycleRegistry.beginPublishing(functionVersionId);

        try {
            if (artifactRegistry.findArtifactMetadata(functionVersionId).isPresent()) {
                throw new IllegalStateException(
                        "Function version already has a published artifact and cannot be republished: " + functionVersionId);
            }

            PublishedArtifact published = artifactPublisher.publish(functionVersionId, preparedArtifactDirectory);

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
