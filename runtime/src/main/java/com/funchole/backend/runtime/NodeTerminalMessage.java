package com.funchole.backend.runtime;

import java.util.UUID;

/**
 * Node Executor -> Java Runtime Worker envelope. One line of
 * newline-delimited JSON over the Node process's stdout.
 */
record NodeTerminalMessage(
        String type,
        UUID executionId,
        String output,
        NodeErrorPayload error
) {
    static final String RESULT = "RESULT";
    static final String ERROR = "ERROR";

    NodeExecutionResult toResult() {
        if (RESULT.equals(type)) {
            return NodeExecutionResult.success(executionId, output);
        }
        if (ERROR.equals(type)) {
            return NodeExecutionResult.failure(
                    executionId,
                    error == null ? "UNKNOWN" : error.code(),
                    error == null ? null : error.message()
            );
        }
        throw new IllegalStateException("Unsupported Node executor message type: " + type);
    }
}
