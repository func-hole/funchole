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
        InvocationStepExecutionStatus status,
        int attempt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
