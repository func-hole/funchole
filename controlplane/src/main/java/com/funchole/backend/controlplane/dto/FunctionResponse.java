package com.funchole.backend.controlplane.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FunctionResponse(
        UUID id,
        String functionKey,
        String name,
        String description,
        String runtime,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
