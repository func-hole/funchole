package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.dto.FlowVersionCreateRequest;
import com.funchole.backend.controlplane.entity.Flow;
import com.funchole.backend.controlplane.entity.FlowVersion;
import com.funchole.backend.controlplane.repository.FlowRepository;
import com.funchole.backend.controlplane.repository.FlowStepRepository;
import com.funchole.backend.controlplane.repository.FlowVersionRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowVersionService {
    private static final String DEFAULT_RUNTIME = "NODE";

    private final FlowVersionRepository flowVersionRepository;
    private final FlowStepRepository flowStepRepository;
    private final FlowRepository flowRepository;
    private final FlowService flowService;

    public FlowVersionService(
            FlowVersionRepository flowVersionRepository,
            FlowStepRepository flowStepRepository,
            FlowRepository flowRepository,
            FlowService flowService
    ) {
        this.flowVersionRepository = flowVersionRepository;
        this.flowStepRepository = flowStepRepository;
        this.flowRepository = flowRepository;
        this.flowService = flowService;
    }

    public Page<FlowVersion> listVersions(UUID appUserId, UUID flowId, int page, int size) {
        flowService.getFlowById(appUserId, flowId);
        Pageable pageable = PageRequest.of(
                Math.max(page - 1, 0),
                Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, FlowVersion::getVersion)
        );
        return flowVersionRepository.findAllByFlow_Id(flowId, pageable);
    }

    public FlowVersion getVersionById(UUID appUserId, UUID flowId, UUID versionId) {
        flowService.getFlowById(appUserId, flowId);
        return flowVersionRepository.findByIdAndFlow_Id(versionId, flowId)
                .orElseThrow(() -> new ResourceNotFoundException("Flow version not found: " + versionId));
    }

    @Transactional
    public FlowVersion createDraftVersion(UUID appUserId, UUID flowId, FlowVersionCreateRequest request) {
        Flow flow = flowService.getFlowById(appUserId, flowId);
        int nextVersion = flowVersionRepository.findMaxVersion(flowId) + 1;
        String runtime = request.runtime() != null ? request.runtime() : DEFAULT_RUNTIME;

        FlowVersion flowVersion = FlowVersion.create(flow, nextVersion, runtime, request.metadata());
        return flowVersionRepository.save(flowVersion);
    }

    @Transactional
    public FlowVersion adoptVersion(UUID appUserId, UUID flowId, UUID versionId) {
        Flow flow = flowService.getFlowById(appUserId, flowId);
        FlowVersion flowVersion = getVersionById(appUserId, flowId, versionId);

        if (flowVersion.getStatus() != FlowVersionStatus.DRAFT) {
            throw new IllegalArgumentException("Only a DRAFT version can be adopted, current status: " + flowVersion.getStatus());
        }
        if (!flowStepRepository.existsByFlowVersion_Id(versionId)) {
            throw new IllegalArgumentException("Cannot adopt a flow version with no steps");
        }

        Optional<FlowVersion> currentlyAdopted = flowVersionRepository.findByFlow_IdAndStatus(flowId, FlowVersionStatus.ADOPTED);
        currentlyAdopted.ifPresent(previous -> {
            previous.archive();
            flowVersionRepository.save(previous);
        });

        flowVersion.adopt();
        FlowVersion savedVersion = flowVersionRepository.save(flowVersion);

        flow.activateVersion(savedVersion.getId(), FlowVersionStatus.ADOPTED.name());
        flowRepository.save(flow);

        return savedVersion;
    }

    @Transactional
    public FlowVersion archiveVersion(UUID appUserId, UUID flowId, UUID versionId) {
        Flow flow = flowService.getFlowById(appUserId, flowId);
        FlowVersion flowVersion = getVersionById(appUserId, flowId, versionId);

        if (flowVersion.getStatus() != FlowVersionStatus.ADOPTED) {
            throw new IllegalArgumentException("Only an ADOPTED version can be archived, current status: " + flowVersion.getStatus());
        }

        flowVersion.archive();
        FlowVersion savedVersion = flowVersionRepository.save(flowVersion);

        flow.clearActiveVersion(savedVersion.getId());
        flowRepository.save(flow);

        return savedVersion;
    }

    @Transactional
    public void deleteDraftVersion(UUID appUserId, UUID flowId, UUID versionId) {
        FlowVersion flowVersion = getVersionById(appUserId, flowId, versionId);

        if (flowVersion.getStatus() != FlowVersionStatus.DRAFT) {
            throw new IllegalArgumentException("Only a DRAFT version can be deleted, current status: " + flowVersion.getStatus());
        }

        flowStepRepository.deleteAllByFlowVersion_Id(versionId);
        flowVersionRepository.delete(flowVersion);
    }
}
