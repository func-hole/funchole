package com.funchole.backend.gateway;

import io.netty.handler.ssl.SslContext;
import java.util.Map;

public record GatewayRegistrySnapshot(
        Map<String, GatewayRuntimeEntry> entriesByHostname,
        SslContext defaultSslContext
) {
}
