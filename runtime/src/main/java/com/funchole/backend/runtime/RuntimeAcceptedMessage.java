package com.funchole.backend.runtime;

import java.util.UUID;

/**
 * Runtime Worker -> Dispatcher envelope. One line of newline-delimited JSON.
 *
 * ACCEPTED means only that this worker took ownership of the execution
 * attempt - it says nothing about Function success or completion.
 */
record RuntimeAcceptedMessage(
        String type,
        UUID executionId
) {

    static final String TYPE = "ACCEPTED";

    static RuntimeAcceptedMessage of(UUID executionId) {
        return new RuntimeAcceptedMessage(TYPE, executionId);
    }
}
