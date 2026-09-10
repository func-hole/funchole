package com.funchole.backend.runtime;

import java.util.UUID;

/**
 * Terminal result of one Node artifact execution attempt.
 */
public record NodeExecutionResult(
        UUID executionId,
        boolean success,
        String output,
        String errorCode,
        String errorMessage
) {
    public static NodeExecutionResult success(UUID executionId, String output) {
        return new NodeExecutionResult(executionId, true, output, null, null);
    }

    public static NodeExecutionResult failure(UUID executionId, String errorCode, String errorMessage) {
        return new NodeExecutionResult(executionId, false, null, errorCode, errorMessage);
    }
}
