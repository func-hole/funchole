package com.funchole.backend.controlplane.dto;

import com.funchole.backend.controlplane.constant.FlowStepComponentType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FlowStepResponse(
        UUID id,
        UUID flowVersionId,
        String stepKey,
        FlowStepComponentType componentType,
        int position,
        UUID componentId,
        UUID componentVersionId,
        String metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
