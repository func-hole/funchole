package com.funchole.backend.invocation;

import java.util.UUID;

public record InvocationStepSnapshot(
        UUID stepId,
        String stepKey,
        String componentType,
        int position,
        UUID componentId,
        UUID componentVersionId,
        String metadata
) {
}
