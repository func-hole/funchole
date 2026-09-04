package com.funchole.backend.gateway;

public record GatewayRequestContext(
        String method,
        String hostname,
        String path,
        String rawUri
) {
}
