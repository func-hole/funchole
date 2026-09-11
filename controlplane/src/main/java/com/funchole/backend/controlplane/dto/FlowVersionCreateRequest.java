package com.funchole.backend.controlplane.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;

public record FlowVersionCreateRequest(
        @Schema(defaultValue = "NODE", example = "NODE")
        @Nullable
        @Size(max = 100, message = "Runtime must be at most 100 characters")
        String runtime,

        @Schema(description = "Raw JSON text stored as-is alongside the version", nullable = true)
        @Nullable
        String metadata
) {
}
