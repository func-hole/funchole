package com.funchole.backend.runtime;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the exact artifact pinned to a componentId/componentVersionId
 * pair - never the latest or active version. A running Invocation must
 * remain stable even if a newer component version is deployed later, so
 * there is deliberately no {@code resolveLatest}/{@code resolveActive}
 * behavior anywhere in this contract.
 */
public interface ArtifactResolver {

    Optional<ArtifactReference> resolve(UUID componentId, UUID componentVersionId);
}
