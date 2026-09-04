package com.funchole.backend.certificate;

public record CertificateBundle(
        byte[] certificateChain,
        byte[] privateKey
) {
}
