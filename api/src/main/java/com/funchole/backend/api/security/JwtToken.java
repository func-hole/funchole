package com.funchole.backend.api.security;

import java.time.OffsetDateTime;

public record JwtToken(
        String token,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt
) {
}
