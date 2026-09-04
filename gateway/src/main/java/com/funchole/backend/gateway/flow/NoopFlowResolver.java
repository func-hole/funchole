package com.funchole.backend.gateway.flow;

import com.funchole.backend.gateway.GatewayRequestContext;
import com.funchole.backend.gateway.GatewayRuntimeEntry;
import java.util.Optional;

public final class NoopFlowResolver implements FlowResolver {

    @Override
    public Optional<FlowResolution> resolve(GatewayRuntimeEntry gateway, GatewayRequestContext requestContext) {
        return Optional.empty();
    }
}
