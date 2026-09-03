package com.funchole.backend.controlplane.dto;

import com.funchole.backend.controlplane.constant.DomainStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DomainResponse(
        UUID id,
        String domainName,
        DomainStatus status,
        String verificationCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
