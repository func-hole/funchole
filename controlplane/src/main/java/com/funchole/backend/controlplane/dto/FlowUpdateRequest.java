package com.funchole.backend.controlplane.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record FlowUpdateRequest(
        @Schema(defaultValue = "Orders List", example = "Orders List")
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name,

        @Schema(defaultValue = "Lists orders for the caller", example = "Lists orders for the caller")
        @Nullable
        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        @NotNull(message = "Gateway id is required")
        UUID gatewayId,

        @Schema(defaultValue = "GET", example = "GET")
        @NotBlank(message = "HTTP method is required")
        @Size(max = 50, message = "HTTP method must be at most 50 characters")
        String httpMethod,

        @Schema(defaultValue = "/orders", example = "/orders")
        @NotBlank(message = "Path is required")
        @Size(max = 2048, message = "Path must be at most 2048 characters")
        @Pattern(regexp = "^/.*", message = "Path must start with '/'")
        String path,

        @Schema(defaultValue = "100", example = "100")
        @Nullable
        Integer priority
) {
}
