package com.funchole.backend.controlplane.dto;

import java.time.OffsetDateTime;

public record AuthTokenResponse(
        String accessToken,
        String tokenType,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        Boolean passwordChangeRequired
) {
}
