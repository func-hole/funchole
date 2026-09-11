package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists FunctionVersion deployment-lifecycle transitions
 * (DRAFT -&gt; PUBLISHING -&gt; READY / FAILED). Each method is its own
 * transaction, committing independently - the caller (FunctionVersionDeploymentService)
 * drives an external, non-transactional artifact publish between the
 * PUBLISHING transition and the terminal READY/FAILED one, so PUBLISHING
 * must already be durable before that call starts.
 */
@Service
public class FunctionVersionLifecycleRegistry {

    private final FunctionVersionRepository functionVersionRepository;

    public FunctionVersionLifecycleRegistry(FunctionVersionRepository functionVersionRepository) {
        this.functionVersionRepository = functionVersionRepository;
    }

    /**
     * Atomically flips DRAFT -&gt; PUBLISHING via a single conditional UPDATE
     * (see {@link FunctionVersionRepository#compareAndSetStatus}), so of any
     * number of concurrent callers racing on the same functionVersionId,
     * exactly one can ever win this transition. A 0-row result only tells us
     * "the transition didn't happen"; the follow-up read exists purely to
     * report an accurate reason (not found vs. wrong status) and plays no
     * part in the atomicity guarantee itself.
     */
    @Transactional
    public FunctionVersion beginPublishing(UUID functionVersionId) {
        int updated = functionVersionRepository.compareAndSetStatus(
                functionVersionId, FunctionVersionStatus.DRAFT, FunctionVersionStatus.PUBLISHING, OffsetDateTime.now());
        if (updated == 0) {
            FunctionVersion existing = load(functionVersionId);
            throw new IllegalStateException(
                    "Function version must be DRAFT to start publishing, current status is "
                            + existing.getStatus() + ": " + functionVersionId);
        }
        return load(functionVersionId);
    }

    @Transactional
    public FunctionVersion markReady(UUID functionVersionId) {
        FunctionVersion functionVersion = load(functionVersionId);
        if (functionVersion.getStatus() != FunctionVersionStatus.PUBLISHING) {
            throw new IllegalStateException(
                    "Function version must be PUBLISHING to become READY, current status is "
                            + functionVersion.getStatus() + ": " + functionVersionId);
        }
        functionVersion.markReady();
        return functionVersionRepository.save(functionVersion);
    }

    @Transactional
    public FunctionVersion markFailed(UUID functionVersionId) {
        FunctionVersion functionVersion = load(functionVersionId);
        if (functionVersion.getStatus() != FunctionVersionStatus.PUBLISHING) {
            throw new IllegalStateException(
                    "Function version must be PUBLISHING to be marked FAILED, current status is "
                            + functionVersion.getStatus() + ": " + functionVersionId);
        }
        functionVersion.markFailed();
        return functionVersionRepository.save(functionVersion);
    }

    private FunctionVersion load(UUID functionVersionId) {
        return functionVersionRepository.findById(functionVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Function version not found: " + functionVersionId));
    }
}
