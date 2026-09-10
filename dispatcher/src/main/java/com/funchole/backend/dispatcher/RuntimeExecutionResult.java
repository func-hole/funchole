package com.funchole.backend.dispatcher;

import java.util.UUID;

public record RuntimeExecutionResult(
        UUID executionId,
        RuntimeExecutionTerminalType terminalType,
        String output,
        RuntimeExecutionError error
) {
    public static RuntimeExecutionResult success(UUID executionId, String output) {
        return new RuntimeExecutionResult(executionId, RuntimeExecutionTerminalType.RESULT, output, null);
    }

    public static RuntimeExecutionResult failure(UUID executionId, RuntimeExecutionError error) {
        return new RuntimeExecutionResult(executionId, RuntimeExecutionTerminalType.ERROR, null, error);
    }
}
