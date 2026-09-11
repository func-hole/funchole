package com.funchole.backend.controlplane.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FlowResponse(
        UUID id,
        UUID gatewayId,
        String gatewayName,
        UUID activeFlowVersionId,
        String activeFlowVersionStatus,
        String flowKey,
        String name,
        String description,
        String httpMethod,
        String path,
        int priority,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
