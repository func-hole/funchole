package com.funchole.backend.controlplane.dto;

import java.util.UUID;

public record ProfileResponse(
        UUID id,
        String username,
        String email,
        String fullName
) {
}
