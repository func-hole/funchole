package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.core.base.response.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, String>> ping() {
        return ApiResponse.success(Map.of(
                "service", "funchole-backend",
                "status", "ok"
        ));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return ApiResponse.success(Map.of(
                "username", principal.getUsername(),
                "userId", principal.getId(),
                "authentication", "jwt"
        ));
    }
}
