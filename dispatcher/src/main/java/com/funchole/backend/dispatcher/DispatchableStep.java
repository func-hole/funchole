package com.funchole.backend.dispatcher;

import java.util.UUID;

public record DispatchableStep(
        UUID invocationId,
        UUID flowId,
        UUID flowVersionId,
        UUID stepId,
        int position,
        String stepKey,
        String componentType,
        UUID componentId,
        UUID componentVersionId
) {
}
