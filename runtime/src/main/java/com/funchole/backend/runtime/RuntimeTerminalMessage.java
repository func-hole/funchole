package com.funchole.backend.runtime;

import java.util.UUID;

record RuntimeTerminalMessage(
        String type,
        UUID executionId,
        String output,
        RuntimeErrorPayload error
) {
    static RuntimeTerminalMessage result(UUID executionId) {
        return new RuntimeTerminalMessage(
                RuntimeTerminalMode.RESULT.name(),
                executionId,
                "{\"ok\":true,\"executionId\":\"" + executionId + "\"}",
                null
        );
    }

    static RuntimeTerminalMessage error(UUID executionId) {
        return new RuntimeTerminalMessage(
                RuntimeTerminalMode.ERROR.name(),
                executionId,
                null,
                new RuntimeErrorPayload("FAKE_RUNTIME_ERROR", "Simulated runtime failure")
        );
    }
}
