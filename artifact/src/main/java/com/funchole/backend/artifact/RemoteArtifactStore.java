package com.funchole.backend.artifact;

import java.util.Optional;
import java.util.UUID;

/**
 * Storage-neutral boundary for locating/fetching the artifact pinned to an
 * exact componentId/componentVersionId pair from a remote backend. Distinct
 * from {@link ArtifactStore}: a remote fetch materializes into a temporary
 * local directory that the caller must close (see {@link RemoteArtifact}),
 * whereas {@link ArtifactStore} resolves to something already stable and
 * permanent (local disk, or a runtime cache).
 */
public interface RemoteArtifactStore {

    Optional<RemoteArtifact> resolve(UUID componentId, UUID componentVersionId);
}
