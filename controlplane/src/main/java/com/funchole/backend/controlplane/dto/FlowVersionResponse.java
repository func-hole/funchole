package com.funchole.backend.controlplane.dto;

import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FlowVersionResponse(
        UUID id,
        UUID flowId,
        int version,
        FlowVersionStatus status,
        String runtime,
        String metadata,
        List<FlowStepResponse> steps,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime adoptedAt,
        OffsetDateTime archivedAt
) {
}
