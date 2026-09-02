package com.funchole.backend.controlplane.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.funchole.backend.controlplane.dto.ProfileResponse;
import com.funchole.backend.controlplane.security.AppUserDetailsService;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.core.base.response.ApiResponse;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final AppUserDetailsService appUserDetailsService;
    
    public ProfileController(AppUserDetailsService appUserDetailsService) {
        this.appUserDetailsService = appUserDetailsService;
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<ProfileResponse> getProfile(@AuthenticationPrincipal AppUserPrincipal appUserPrincipal) {
        UUID userId = appUserPrincipal.getId();
        AppUserPrincipal principal = appUserDetailsService.loadUserById(userId);
        
        return ApiResponse.success(new ProfileResponse(
            principal.getId(),
            principal.getUsername(),
            principal.getEmail(),
            principal.getFullName()
        ));
    }

    
}
