package com.funchole.backend.dispatcher;

import java.util.UUID;

/**
 * Runtime Worker -> Dispatcher envelope. One line of newline-delimited JSON.
 */
record IpcAcceptedMessage(
        String type,
        UUID executionId
) {

    static final String TYPE = "ACCEPTED";
}
