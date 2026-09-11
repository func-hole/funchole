package com.funchole.backend.controlplane.dto;

import com.funchole.backend.controlplane.constant.FlowStepComponentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record FlowStepCreateRequest(
        @Schema(defaultValue = "list-orders", example = "list-orders")
        @NotBlank(message = "Step key is required")
        @Size(max = 150, message = "Step key must be at most 150 characters")
        String stepKey,

        @NotNull(message = "Component type is required")
        FlowStepComponentType componentType,

        @NotNull(message = "Position is required")
        Integer position,

        @NotNull(message = "Component id is required")
        UUID componentId,

        @NotNull(message = "Component version id is required")
        UUID componentVersionId,

        @Schema(description = "Raw JSON text stored as-is alongside the step", nullable = true)
        @Nullable
        String metadata
) {
}
