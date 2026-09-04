package com.funchole.backend.gateway.flow;

import com.funchole.backend.gateway.GatewayRequestContext;
import com.funchole.backend.gateway.GatewayRuntimeEntry;
import java.util.Optional;

public interface FlowResolver {

    Optional<FlowResolution> resolve(GatewayRuntimeEntry gateway, GatewayRequestContext requestContext);
}
