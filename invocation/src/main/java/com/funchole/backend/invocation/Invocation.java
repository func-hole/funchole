package com.funchole.backend.invocation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Invocation(
        UUID invocationId,
        InvocationKind kind,
        UUID flowId,
        String flowKey,
        UUID flowVersionId,
        InvocationStatus status,
        String inputPayload,
        String dependencySnapshot,
        String result,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt
) {
}
