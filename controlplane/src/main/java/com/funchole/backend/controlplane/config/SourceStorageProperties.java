package com.funchole.backend.controlplane.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.source")
public record SourceStorageProperties(@NotBlank String storageRoot) {
}
