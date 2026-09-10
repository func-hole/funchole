package com.funchole.backend.runtime;

import java.util.UUID;

record RuntimeTerminalMessage(
        String type,
        UUID executionId,
        String output,
        RuntimeErrorPayload error
) {
    static final String RESULT = "RESULT";
    static final String ERROR = "ERROR";

    static RuntimeTerminalMessage result(UUID executionId, String output) {
        return new RuntimeTerminalMessage(RESULT, executionId, output, null);
    }

    static RuntimeTerminalMessage error(UUID executionId, String code, String message) {
        return new RuntimeTerminalMessage(ERROR, executionId, null, new RuntimeErrorPayload(code, message));
    }

    static RuntimeTerminalMessage from(NodeExecutionResult result) {
        return result.success()
                ? result(result.executionId(), result.output())
                : error(result.executionId(), result.errorCode(), result.errorMessage());
    }
}
