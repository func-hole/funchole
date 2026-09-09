package com.funchole.backend.gateway.flow;

import java.util.UUID;

public record FlowResolution(
        UUID flowId,
        String flowKey,
        UUID flowVersionId
) {
}
