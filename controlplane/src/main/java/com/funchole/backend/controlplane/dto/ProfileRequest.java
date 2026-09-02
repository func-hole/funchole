package com.funchole.backend.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record ProfileRequest(
    @Nullable
    @NotBlank(message = "Full name must not be empty")
    @Size(min = 2, message = "Full name must be at least 2 characters")
    String fullName,
    @Nullable
    @NotBlank(message = "Password must not be empty")
    @Size(min = 8, message = "Password must be at least 8 characters")
    String password
) { }
