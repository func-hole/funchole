package com.funchole.backend.gateway;

import com.funchole.backend.certificate.CertificateProvider;
import java.util.UUID;

public record GatewayCertificateRecord(
        UUID gatewayId,
        String gatewayName,
        String gatewayKey,
        String domainName,
        String hostname,
        String secretRef,
        CertificateProvider certificateProvider
) {
}
