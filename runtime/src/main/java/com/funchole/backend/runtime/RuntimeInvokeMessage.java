package com.funchole.backend.runtime;

import java.util.UUID;

/**
 * Dispatcher -> Runtime Worker envelope. One line of newline-delimited JSON.
 */
record RuntimeInvokeMessage(
        String type,
        UUID executionId,
        RuntimeInvokePayload payload
) {

    static final String TYPE = "INVOKE";
}
