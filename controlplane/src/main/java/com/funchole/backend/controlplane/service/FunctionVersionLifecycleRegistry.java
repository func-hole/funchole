package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
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

    @Transactional
    public FunctionVersion beginPublishing(UUID functionVersionId) {
        FunctionVersion functionVersion = load(functionVersionId);
        if (functionVersion.getStatus() != FunctionVersionStatus.DRAFT) {
            throw new IllegalStateException(
                    "Function version must be DRAFT to start publishing, current status is "
                            + functionVersion.getStatus() + ": " + functionVersionId);
        }
        functionVersion.markPublishing();
        return functionVersionRepository.save(functionVersion);
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
