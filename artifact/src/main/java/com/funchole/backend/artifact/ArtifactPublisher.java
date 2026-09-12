package com.funchole.backend.artifact;

import java.nio.file.Path;
import java.util.UUID;

public interface ArtifactPublisher {

    PublishedArtifact publish(UUID componentVersionId, Path preparedArtifactDirectory);

    /**
     * Removes a previously published artifact - the compensating action a
     * caller takes when a deployment fails after {@link #publish} has
     * already succeeded, so the remote object is not left orphaned and
     * untracked. Implementations know nothing about why they are being
     * asked to delete; the caller decides that.
     */
    void delete(UUID componentVersionId, String objectKey);
}
