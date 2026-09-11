package com.funchole.backend.artifact;

import java.nio.file.Path;
import java.util.UUID;

public interface ArtifactPublisher {

    PublishedArtifact publish(UUID componentVersionId, Path preparedArtifactDirectory);
}
