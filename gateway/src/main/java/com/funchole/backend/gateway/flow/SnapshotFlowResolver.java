package com.funchole.backend.gateway.flow;

import com.funchole.backend.gateway.GatewayRegistry;
import com.funchole.backend.gateway.GatewayRequestContext;
import com.funchole.backend.gateway.GatewayRuntimeEntry;
import java.util.Optional;

public final class SnapshotFlowResolver implements FlowResolver {

    private final GatewayRegistry gatewayRegistry;

    public SnapshotFlowResolver(GatewayRegistry gatewayRegistry) {
        this.gatewayRegistry = gatewayRegistry;
    }

    @Override
    public Optional<FlowResolution> resolve(GatewayRuntimeEntry gateway, GatewayRequestContext requestContext) {
        return gatewayRegistry
                .routingFor(gateway.gatewayId())
                .resolve(requestContext.method(), requestContext.path());
    }
}
