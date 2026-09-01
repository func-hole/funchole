package com.funchole.backend.controlplane.security;

import java.time.OffsetDateTime;

public record JwtToken(
        String token,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt
) {
}
