package com.funchole.backend.invocation;

import java.util.List;
import java.util.UUID;

public record InvocationSnapshot(
        UUID rootFlowId,
        String rootFlowKey,
        UUID rootFlowVersionId,
        List<InvocationFlowSnapshot> flows
) {
}
