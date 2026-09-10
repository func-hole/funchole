package com.funchole.backend.runtime;

import java.util.UUID;

/**
 * Java Runtime Worker -> Node Executor envelope. One line of
 * newline-delimited JSON over the Node process's stdin.
 */
record NodeExecuteMessage(
        String type,
        UUID executionId,
        UUID componentId,
        UUID componentVersionId,
        String artifactPath,
        String input
) {

    static final String TYPE = "EXECUTE";

    static NodeExecuteMessage from(NodeExecutionRequest request) {
        return new NodeExecuteMessage(
                TYPE,
                request.executionId(),
                request.componentId(),
                request.componentVersionId(),
                request.artifactPath().toString(),
                request.input()
        );
    }
}
