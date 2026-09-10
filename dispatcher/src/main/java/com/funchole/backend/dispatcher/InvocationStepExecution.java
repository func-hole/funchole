package com.funchole.backend.dispatcher;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvocationStepExecution(
        UUID id,
        UUID invocationId,
        UUID flowId,
        UUID flowVersionId,
        UUID stepId,
        int position,
        String componentType,
        UUID componentId,
        UUID componentVersionId,
        String runtimeType,
        String runtimeInstanceId,
        InvocationStepExecutionStatus status,
        int attempt,
        String result,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
}
