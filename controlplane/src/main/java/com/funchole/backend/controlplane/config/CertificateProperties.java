package com.funchole.backend.controlplane.config;

import com.funchole.backend.certificate.CertificateProvider;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.certificate")
public record CertificateProperties(
        @NotNull CertificateProvider provider,
        @Min(1) long selfSignedValidityDays,
        @Min(1000) long retryDelayMs
) {
}
