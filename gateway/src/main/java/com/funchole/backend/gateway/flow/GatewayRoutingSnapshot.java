package com.funchole.backend.gateway.flow;

import java.util.Map;
import java.util.Optional;

public record GatewayRoutingSnapshot(Map<RouteKey, FlowResolution> routesByKey) {

    public static final GatewayRoutingSnapshot EMPTY = new GatewayRoutingSnapshot(Map.of());

    public Optional<FlowResolution> resolve(String method, String path) {
        return Optional.ofNullable(routesByKey.get(new RouteKey(method, path)));
    }
}
