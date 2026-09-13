package com.funchole.backend.controlplane.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FunctionUpdateRequest(
        @Schema(defaultValue = "Hello World", example = "Hello World")
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name,

        @Schema(defaultValue = "Says hello", example = "Says hello")
        @Nullable
        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        @Schema(defaultValue = "NODE", example = "NODE")
        @Nullable
        @Size(max = 100, message = "Runtime must be at most 100 characters")
        String runtime
) {
}
