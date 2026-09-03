package com.funchole.backend.controlplane.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record AuthRequest(
        @NotBlank 
        @Schema(defaultValue = "admin", example = "admin")
        String username,

        @NotBlank 
        @Schema(defaultValue = "admin12345", example = "admin12345")
        String password
) {
}
