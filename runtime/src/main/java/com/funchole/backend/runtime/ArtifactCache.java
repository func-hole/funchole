package com.funchole.backend.runtime;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * A local, stable cache of runtime artifacts, keyed exclusively by the exact
 * {@code componentVersionId}.
 *
 * Purpose: once a remote {@link ArtifactStore} (e.g. S3-compatible) is
 * introduced, it will fetch an artifact once, {@link #put} it here, and every
 * subsequent execution of the same pinned version resolves from local disk
 * instead of the network.
 *
 * Exact-version semantics only: no latest lookup, no active-version lookup,
 * no fallback scanning. Cache entries always resolve to a locally executable
 * {@link ArtifactReference}, so the Node execution layer never learns
 * whether an artifact came from the cache, local disk, or a remote store.
 */
public interface ArtifactCache {

    /**
     * @return the cached artifact reference for the exact pinned version, or
     *         empty on a cache miss
     */
    Optional<ArtifactReference> resolve(UUID componentId, UUID componentVersionId);

    /**
     * Copies an already-materialized local artifact (directory convention:
     * {@code <sourceArtifactDir>/index.mjs}) into the cache under the pinned
     * version. Idempotent: putting the same componentId/componentVersionId
     * again returns the existing cached reference.
     */
    ArtifactReference put(UUID componentId, UUID componentVersionId, Path sourceArtifactDirectory);
}
