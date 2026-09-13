package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.dto.FunctionCreateRequest;
import com.funchole.backend.controlplane.dto.FunctionResponse;
import com.funchole.backend.controlplane.dto.FunctionUpdateRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.mapper.FunctionMapper;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.FunctionService;
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
@RequestMapping("/api/v1/functions")
public class FunctionController {
    private final FunctionService functionService;
    private final ProfileService profileService;
    private final FunctionMapper functionMapper;
    private final PaginationMapper paginationMapper;

    public FunctionController(
            FunctionService functionService,
            ProfileService profileService,
            FunctionMapper functionMapper,
            PaginationMapper paginationMapper
    ) {
        this.functionService = functionService;
        this.profileService = profileService;
        this.functionMapper = functionMapper;
        this.paginationMapper = paginationMapper;
    }

    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<PaginationResponse<FunctionResponse>> listFunctions(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<FunctionResponse> functions = functionService.listFunctions(appUserPrincipal.getId(), page, size)
                .map(functionMapper::toResponse);
        return ApiResponse.success(paginationMapper.toResponse(functions));
    }

    @GetMapping("/{functionId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FunctionResponse> getFunctionById(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID functionId
    ) {
        Function function = functionService.getFunctionById(appUserPrincipal.getId(), functionId);
        return ApiResponse.success(functionMapper.toResponse(function));
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FunctionResponse> createFunction(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @Valid @RequestBody FunctionCreateRequest request
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());
        Function function = functionService.createFunction(appUser, request);
        return ApiResponse.success(functionMapper.toResponse(function));
    }

    @PutMapping("/{functionId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FunctionResponse> updateFunction(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID functionId,
            @Valid @RequestBody FunctionUpdateRequest request
    ) {
        Function function = functionService.updateFunction(appUserPrincipal.getId(), functionId, request);
        return ApiResponse.success(functionMapper.toResponse(function));
    }

    @DeleteMapping("/{functionId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Map<String, String>> deleteFunction(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID functionId
    ) {
        functionService.deleteFunction(appUserPrincipal.getId(), functionId);
        return ApiResponse.success(Map.of("message", "Function deleted successfully"));
    }
}
