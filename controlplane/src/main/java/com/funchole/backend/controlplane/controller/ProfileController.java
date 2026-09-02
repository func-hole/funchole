package com.funchole.backend.controlplane.controller;

import java.util.UUID;

import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.funchole.backend.controlplane.dto.ProfileResponse;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.security.AppUserDetailsService;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.ProfileService;
import com.funchole.backend.core.base.response.ApiResponse;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;
    
    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<ProfileResponse> getProfile(@AuthenticationPrincipal AppUserPrincipal appUserPrincipal) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());

        return ApiResponse.success(new ProfileResponse(
            appUser.getId(),
            appUser.getUsername(),
            appUser.getEmail(),
            appUser.getFullName()
        ));
    }

    
}
