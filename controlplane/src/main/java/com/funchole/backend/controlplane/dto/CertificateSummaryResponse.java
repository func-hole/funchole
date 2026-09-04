package com.funchole.backend.controlplane.dto;

import com.funchole.backend.certificate.CertificateProvider;
import com.funchole.backend.certificate.CertificateStatus;
import java.time.OffsetDateTime;

public record CertificateSummaryResponse(
        String hostname,
        String wildcardHostname,
        CertificateProvider provider,
        CertificateStatus status,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt
) {
}
