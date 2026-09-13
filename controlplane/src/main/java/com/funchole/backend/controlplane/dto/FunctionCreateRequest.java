package com.funchole.backend.controlplane.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FunctionCreateRequest(
        @Schema(defaultValue = "fn_hello_world", example = "fn_hello_world")
        @NotBlank(message = "Function key is required")
        @Size(max = 150, message = "Function key must be at most 150 characters")
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "Function key may only contain letters, numbers, '_', '.' and '-'")
        String functionKey,

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
