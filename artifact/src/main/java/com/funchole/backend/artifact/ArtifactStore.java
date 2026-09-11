package com.funchole.backend.artifact;

import java.util.Optional;
import java.util.UUID;

/**
 * Storage-neutral boundary for locating/fetching the artifact pinned to an
 * exact componentId/componentVersionId pair. The local directory is the
 * first implementation ({@link LocalArtifactStore}); future backends (e.g.
 * object storage) will slot in behind this interface.
 *
 * Exact-version semantics only: a running Invocation must remain stable
 * even if a newer component version is deployed later, so there is
 * deliberately no {@code resolveLatest}/{@code resolveActive}/directory
 * scanning behavior anywhere in this contract.
 */
public interface ArtifactStore {

    /**
     * Locates/fetches the artifact pinned to the exact component
     * version. Callers receive a locally executable artifact reference
     * ({@link ArtifactReference}) - the Node execution layer never needs to
     * know whether the artifact came from local disk or another backend.
     */
    Optional<ArtifactReference> resolve(UUID componentId, UUID componentVersionId);
}
