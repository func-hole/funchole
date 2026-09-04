package com.funchole.backend.certificate;

import java.util.List;

public record CertificateRequest(
        String commonName,
        List<String> subjectAlternativeNames
) {
}
