package com.funchole.backend.controlplane.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DomainCreateRequest(
    @Schema(defaultValue = "example.com", example = "example.com")
    @NotBlank(message = "Domain name is required")
    @Pattern(
            regexp = "^(?=.{1,253}$)(?!-)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$",
            message = "Invalid domain name"
    )
    String domainName
) {
}
