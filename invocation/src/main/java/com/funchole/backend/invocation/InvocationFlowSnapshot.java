package com.funchole.backend.invocation;

import java.util.List;
import java.util.UUID;

public record InvocationFlowSnapshot(
        UUID flowId,
        String flowKey,
        UUID flowVersionId,
        int version,
        String status,
        String runtime,
        String metadata,
        List<InvocationStepSnapshot> steps
) {
}
