package com.funchole.backend.controlplane.service;

import com.funchole.backend.artifact.PublishedArtifact;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic, database-only finalization of a FunctionVersion deployment:
 * attaches the published artifact metadata and transitions the version from
 * PUBLISHING to READY in ONE local transaction.
 *
 * The remote artifact publish itself stays OUTSIDE this boundary (see
 * {@link FunctionVersionDeploymentService}: remote publish -&gt; this
 * finalization). That split is deliberate: PostgreSQL and the remote object
 * store cannot share a transaction, so the correct primitive is
 *
 * <pre>
 * remote publish succeeds                 (outside the DB)
 *      -&gt; atomic local finalization        (this class, one DB transaction)
 *      -&gt; compensation deletes the remote object only if finalization FAILED
 * </pre>
 *
 * Consequences, all guaranteed by this single transaction:
 * <ul>
 *   <li>a committed finalization means BOTH the artifact metadata and READY
 *       status exist (never one without the other);</li>
 *   <li>a failed finalization persists NOTHING - no partial metadata, no
 *       partial status change - so compensation can never delete an artifact
 *       whose metadata is still referenced by the database.</li>
 * </ul>
 *
 * Immutability is enforced here too: once artifact metadata is attached, no
 * further finalization of the same FunctionVersion can ever happen.
 */
@Service
public class FunctionVersionDeploymentFinalizer {

    private final FunctionVersionRepository functionVersionRepository;

    public FunctionVersionDeploymentFinalizer(FunctionVersionRepository functionVersionRepository) {
        this.functionVersionRepository = functionVersionRepository;
    }

    /**
     * Verifies and persists the finalized deployment:
     * <ol>
     *   <li>the exact FunctionVersion still exists,</li>
     *   <li>its status is still PUBLISHING,</li>
     *   <li>no artifact metadata is attached yet (immutability),</li>
     *   <li>the {@link PublishedArtifact} belongs to this exact
     *       FunctionVersion (id/objectKey validation before commit),</li>
     *   <li>artifact object key / SHA-256 / size are persisted,</li>
     *   <li>the version transitions to READY,</li>
     * </ol>
     * all inside one transaction - either every change commits or none do.
     */
    @Transactional
    public FunctionVersion finalizeDeployment(UUID functionVersionId, PublishedArtifact published) {
        if (published == null) {
            throw new IllegalArgumentException("published artifact is required");
        }
        FunctionVersion functionVersion = functionVersionRepository.findById(functionVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Function version not found: " + functionVersionId));
        if (functionVersion.getStatus() != FunctionVersionStatus.PUBLISHING) {
            throw new IllegalStateException("Function version must be PUBLISHING to be finalized, current status is "
                    + functionVersion.getStatus() + ": " + functionVersionId);
        }
        if (functionVersion.getArtifactMetadata().isPresent()) {
            throw new IllegalStateException(
                    "Function version already has a published artifact and cannot be republished: " + functionVersionId);
        }
        if (!functionVersionId.equals(published.componentVersionId())) {
            throw new IllegalStateException("Published artifact version id " + published.componentVersionId()
                    + " does not match requested function version id " + functionVersionId);
        }
        FunctionVersionArtifactRegistry.validateArtifactReference(
                functionVersionId,
                published.objectKey(),
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                published.sha256(),
                published.sizeBytes());

        functionVersion.attachArtifact(
                published.objectKey(),
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                published.sha256(),
                published.sizeBytes());
        functionVersion.markReady();
        return functionVersionRepository.save(functionVersion);
    }
}
