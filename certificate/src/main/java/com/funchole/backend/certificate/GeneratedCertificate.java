package com.funchole.backend.certificate;

import java.time.OffsetDateTime;

public record GeneratedCertificate(
        CertificateBundle bundle,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt
) {
}
