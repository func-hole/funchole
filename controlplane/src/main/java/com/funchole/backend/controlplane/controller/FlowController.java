package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.dto.FlowCreateRequest;
import com.funchole.backend.controlplane.dto.FlowResponse;
import com.funchole.backend.controlplane.dto.FlowUpdateRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Flow;
import com.funchole.backend.controlplane.mapper.FlowMapper;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.FlowService;
import com.funchole.backend.controlplane.service.ProfileService;
import com.funchole.backend.core.base.mapper.PaginationMapper;
import com.funchole.backend.core.base.response.ApiResponse;
import com.funchole.backend.core.base.response.PaginationResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flows")
public class FlowController {
    private final FlowService flowService;
    private final ProfileService profileService;
    private final FlowMapper flowMapper;
    private final PaginationMapper paginationMapper;

    public FlowController(
            FlowService flowService,
            ProfileService profileService,
            FlowMapper flowMapper,
            PaginationMapper paginationMapper
    ) {
        this.flowService = flowService;
        this.profileService = profileService;
        this.flowMapper = flowMapper;
        this.paginationMapper = paginationMapper;
    }

    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<PaginationResponse<FlowResponse>> listFlows(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<FlowResponse> flows = flowService.listFlows(appUserPrincipal.getId(), page, size)
                .map(flowMapper::toResponse);
        return ApiResponse.success(paginationMapper.toResponse(flows));
    }

    @GetMapping("/{flowId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FlowResponse> getFlowById(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId
    ) {
        Flow flow = flowService.getFlowById(appUserPrincipal.getId(), flowId);
        return ApiResponse.success(flowMapper.toResponse(flow));
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FlowResponse> createFlow(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @Valid @RequestBody FlowCreateRequest request
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());
        Flow flow = flowService.createFlow(appUser, request);
        return ApiResponse.success(flowMapper.toResponse(flow));
    }

    @PutMapping("/{flowId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FlowResponse> updateFlow(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @Valid @RequestBody FlowUpdateRequest request
    ) {
        Flow flow = flowService.updateFlow(appUserPrincipal.getId(), flowId, request);
        return ApiResponse.success(flowMapper.toResponse(flow));
    }

    @DeleteMapping("/{flowId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Map<String, String>> deleteFlow(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId
    ) {
        flowService.deleteFlow(appUserPrincipal.getId(), flowId);
        return ApiResponse.success(Map.of("message", "Flow deleted successfully"));
    }
}
