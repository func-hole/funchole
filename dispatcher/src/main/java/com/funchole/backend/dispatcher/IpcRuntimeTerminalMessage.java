package com.funchole.backend.dispatcher;

import java.util.UUID;

record IpcRuntimeTerminalMessage(
        String type,
        UUID executionId,
        String output,
        IpcRuntimeErrorPayload error
) {
    static final String RESULT = "RESULT";
    static final String ERROR = "ERROR";

    RuntimeExecutionResult toRuntimeExecutionResult() {
        if (RESULT.equals(type)) {
            return RuntimeExecutionResult.success(executionId, output);
        }
        if (ERROR.equals(type)) {
            return RuntimeExecutionResult.failure(executionId, error == null ? null : error.toRuntimeError());
        }
        throw new IllegalStateException("Unsupported terminal IPC message type: " + type);
    }
}
