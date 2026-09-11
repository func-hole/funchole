package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.dto.FlowStepCreateRequest;
import com.funchole.backend.controlplane.dto.FlowStepResponse;
import com.funchole.backend.controlplane.dto.FlowStepUpdateRequest;
import com.funchole.backend.controlplane.entity.FlowStep;
import com.funchole.backend.controlplane.mapper.FlowStepMapper;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.FlowStepService;
import com.funchole.backend.core.base.response.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flows/{flowId}/versions/{versionId}/steps")
public class FlowStepController {
    private final FlowStepService flowStepService;
    private final FlowStepMapper flowStepMapper;

    public FlowStepController(FlowStepService flowStepService, FlowStepMapper flowStepMapper) {
        this.flowStepService = flowStepService;
        this.flowStepMapper = flowStepMapper;
    }

    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<List<FlowStepResponse>> listSteps(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @PathVariable UUID versionId
    ) {
        List<FlowStepResponse> steps = flowStepService.listSteps(appUserPrincipal.getId(), flowId, versionId)
                .stream()
                .map(flowStepMapper::toResponse)
                .toList();
        return ApiResponse.success(steps);
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FlowStepResponse> createStep(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @PathVariable UUID versionId,
            @Valid @RequestBody FlowStepCreateRequest request
    ) {
        FlowStep flowStep = flowStepService.createStep(appUserPrincipal.getId(), flowId, versionId, request);
        return ApiResponse.success(flowStepMapper.toResponse(flowStep));
    }

    @PutMapping("/{stepId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FlowStepResponse> updateStep(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @PathVariable UUID versionId,
            @PathVariable UUID stepId,
            @Valid @RequestBody FlowStepUpdateRequest request
    ) {
        FlowStep flowStep = flowStepService.updateStep(appUserPrincipal.getId(), flowId, versionId, stepId, request);
        return ApiResponse.success(flowStepMapper.toResponse(flowStep));
    }

    @DeleteMapping("/{stepId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Map<String, String>> deleteStep(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @PathVariable UUID versionId,
            @PathVariable UUID stepId
    ) {
        flowStepService.deleteStep(appUserPrincipal.getId(), flowId, versionId, stepId);
        return ApiResponse.success(Map.of("message", "Flow step deleted successfully"));
    }
}
