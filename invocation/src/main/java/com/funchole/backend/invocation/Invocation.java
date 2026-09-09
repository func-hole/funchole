package com.funchole.backend.invocation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Invocation(
        UUID invocationId,
        UUID flowId,
        String flowKey,
        UUID flowVersionId,
        InvocationStatus status,
        String inputPayload,
        String dependencySnapshot,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
