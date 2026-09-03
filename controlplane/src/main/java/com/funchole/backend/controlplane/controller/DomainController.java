package com.funchole.backend.controlplane.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.funchole.backend.controlplane.dto.DomainCreateRequest;
import com.funchole.backend.controlplane.dto.DomainResponse;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.mapper.DomainMapper;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.DomainService;
import com.funchole.backend.controlplane.service.ProfileService;
import com.funchole.backend.core.base.response.ApiResponse;
import com.funchole.backend.core.base.response.PaginationResponse;
import com.funchole.backend.core.base.mapper.PaginationMapper;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/domains")
public class DomainController {
    private final DomainService domainService;
    private final ProfileService profileService;
    private final DomainMapper domainMapper;
    private final PaginationMapper paginationMapper;

    public DomainController(
            DomainService domainService,
            ProfileService profileService,
            DomainMapper domainMapper,
            PaginationMapper paginationMapper
    ) {
        this.domainService = domainService;
        this.profileService = profileService;
        this.domainMapper = domainMapper;
        this.paginationMapper = paginationMapper;
    }

    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<PaginationResponse<DomainResponse>> listDomains(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<DomainResponse> domains = domainService.listDomains(appUserPrincipal.getId(), page, size)
                .map(domainMapper::toResponse);

        return ApiResponse.success(paginationMapper.toResponse(domains));
    }

    @GetMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<DomainResponse> getDomainById(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID id
    ) {
        AppDomain appDomain = domainService.getDomainById(appUserPrincipal.getId(), id);
        return ApiResponse.success(domainMapper.toResponse(appDomain));
    }

    @PostMapping("/{id}/verification")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<DomainResponse> initiateDomainVerification(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID id
    ) {
        AppDomain appDomain = domainService.initiateDomainVerification(appUserPrincipal.getId(), id);
        return ApiResponse.success(domainMapper.toResponse(appDomain));
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<DomainResponse> createDomain(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @Valid @RequestBody DomainCreateRequest request
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());
        AppDomain appDomain = domainService.createDomain(appUser, request);
        return ApiResponse.success(domainMapper.toResponse(appDomain));
    }
}
