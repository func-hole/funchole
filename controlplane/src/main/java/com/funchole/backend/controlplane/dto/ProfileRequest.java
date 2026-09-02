package com.funchole.backend.controlplane.dto;

import org.jspecify.annotations.Nullable;

public record ProfileRequest(
    @Nullable String fullName,
    @Nullable String password
) { }
