package com.funchole.backend.controlplane.dto;

import com.funchole.backend.controlplane.constant.GatewayStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record GatewayResponse(
        UUID id,
        UUID appDomainId,
        String domainName,
        String name,
        String uniqueKey,
        String description,
        GatewayStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
