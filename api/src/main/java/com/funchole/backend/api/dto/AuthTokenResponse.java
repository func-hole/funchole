package com.funchole.backend.api.dto;

import java.time.OffsetDateTime;

public record AuthTokenResponse(
        String accessToken,
        String tokenType,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt
) {
}
