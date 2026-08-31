package com.funchole.backend.core.base.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BaseDto(
        UUID id,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
