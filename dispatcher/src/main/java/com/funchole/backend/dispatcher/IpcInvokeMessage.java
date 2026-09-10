package com.funchole.backend.dispatcher;

import java.util.UUID;

/**
 * Dispatcher -> Runtime Worker envelope. One line of newline-delimited JSON.
 */
record IpcInvokeMessage(
        String type,
        UUID executionId,
        IpcInvokePayload payload
) {

    static final String TYPE = "INVOKE";

    static IpcInvokeMessage from(RuntimeExecutionRequest request) {
        return new IpcInvokeMessage(TYPE, request.executionId(), IpcInvokePayload.from(request));
    }
}
