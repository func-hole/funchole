package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.dto.FlowStepCreateRequest;
import com.funchole.backend.controlplane.dto.FlowStepUpdateRequest;
import com.funchole.backend.controlplane.entity.FlowStep;
import com.funchole.backend.controlplane.entity.FlowVersion;
import com.funchole.backend.controlplane.repository.FlowStepRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowStepService {

    private final FlowStepRepository flowStepRepository;
    private final FlowVersionService flowVersionService;

    public FlowStepService(FlowStepRepository flowStepRepository, FlowVersionService flowVersionService) {
        this.flowStepRepository = flowStepRepository;
        this.flowVersionService = flowVersionService;
    }

    public List<FlowStep> listSteps(UUID appUserId, UUID flowId, UUID versionId) {
        flowVersionService.getVersionById(appUserId, flowId, versionId);
        return flowStepRepository.findAllByFlowVersion_IdOrderByPosition(versionId);
    }

    @Transactional
    public FlowStep createStep(UUID appUserId, UUID flowId, UUID versionId, FlowStepCreateRequest request) {
        FlowVersion flowVersion = getDraftVersion(appUserId, flowId, versionId);

        FlowStep flowStep = FlowStep.create(
                flowVersion,
                request.stepKey(),
                request.componentType(),
                request.position(),
                request.componentId(),
                request.componentVersionId(),
                request.metadata()
        );

        return flowStepRepository.save(flowStep);
    }

    @Transactional
    public FlowStep updateStep(UUID appUserId, UUID flowId, UUID versionId, UUID stepId, FlowStepUpdateRequest request) {
        getDraftVersion(appUserId, flowId, versionId);
        FlowStep flowStep = getStep(versionId, stepId);

        flowStep.update(
                request.stepKey(),
                request.componentType(),
                request.position(),
                request.componentId(),
                request.componentVersionId(),
                request.metadata()
        );

        return flowStepRepository.save(flowStep);
    }

    @Transactional
    public void deleteStep(UUID appUserId, UUID flowId, UUID versionId, UUID stepId) {
        getDraftVersion(appUserId, flowId, versionId);
        FlowStep flowStep = getStep(versionId, stepId);
        flowStepRepository.delete(flowStep);
    }

    private FlowVersion getDraftVersion(UUID appUserId, UUID flowId, UUID versionId) {
        FlowVersion flowVersion = flowVersionService.getVersionById(appUserId, flowId, versionId);
        if (flowVersion.getStatus() != FlowVersionStatus.DRAFT) {
            throw new IllegalArgumentException(
                    "Steps can only be changed while the flow version is DRAFT, current status: " + flowVersion.getStatus());
        }
        return flowVersion;
    }

    private FlowStep getStep(UUID versionId, UUID stepId) {
        return flowStepRepository.findByIdAndFlowVersion_Id(stepId, versionId)
                .orElseThrow(() -> new ResourceNotFoundException("Flow step not found: " + stepId));
    }
}
