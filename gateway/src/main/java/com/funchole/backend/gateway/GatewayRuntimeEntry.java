package com.funchole.backend.gateway;

import com.funchole.backend.certificate.CertificateProvider;
import io.netty.handler.ssl.SslContext;
import java.util.UUID;

public record GatewayRuntimeEntry(
        UUID gatewayId,
        String gatewayName,
        String gatewayKey,
        String domainName,
        String hostname,
        CertificateProvider certificateProvider,
        SslContext sslContext
) {
}
