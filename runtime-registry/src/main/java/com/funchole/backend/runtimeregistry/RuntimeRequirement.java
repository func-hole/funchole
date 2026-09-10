package com.funchole.backend.runtimeregistry;

import java.util.Locale;

/**
 * What runtime capability a Step Execution needs.
 *
 * Only {@code runtimeType} is modeled today because the current Flow/Invocation
 * schema only tracks a single runtime string per flow version (e.g. "NODE").
 * There is no runtime version concept anywhere upstream yet, so no
 * runtimeVersion field is introduced here.
 */
public record RuntimeRequirement(String runtimeType) {

    public RuntimeRequirement {
        if (runtimeType == null || runtimeType.isBlank()) {
            throw new IllegalArgumentException("runtimeType is required");
        }
        runtimeType = runtimeType.trim().toUpperCase(Locale.ROOT);
    }
}
