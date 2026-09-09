package com.funchole.backend.gateway;

import com.funchole.backend.gateway.flow.GatewayRoutingSnapshot;
import io.netty.handler.ssl.SslContext;
import java.util.Map;
import java.util.UUID;

public record GatewayRegistrySnapshot(
        Map<String, GatewayRuntimeEntry> entriesByHostname,
        SslContext defaultSslContext,
        Map<UUID, GatewayRoutingSnapshot> routingByGatewayId
) {
}
