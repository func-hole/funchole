package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.runtime.ArtifactPublisher;
import com.funchole.backend.runtime.PublishedArtifact;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Orchestrates publishing a prepared artifact directory for a FunctionVersion.
 * Packaging/checksum/upload stays in {@link ArtifactPublisher}; persistence and
 * immutability stay in {@link FunctionVersionArtifactRegistry}. This class only
 * sequences the two and validates the hand-off between them.
 */
public class FunctionVersionDeploymentService {

    private final ArtifactPublisher artifactPublisher;
    private final FunctionVersionArtifactRegistry artifactRegistry;

    public FunctionVersionDeploymentService(ArtifactPublisher artifactPublisher, FunctionVersionArtifactRegistry artifactRegistry) {
        this.artifactPublisher = artifactPublisher;
        this.artifactRegistry = artifactRegistry;
    }

    public FunctionVersion deployArtifact(UUID functionVersionId, Path preparedArtifactDirectory) {
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

        return artifactRegistry.attachPublishedArtifact(
                functionVersionId,
                published.objectKey(),
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                published.sha256(),
                published.sizeBytes()
        );
    }
}
