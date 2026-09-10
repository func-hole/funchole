package com.funchole.backend.dispatcher;

/**
 * A local IPC handoff to a Runtime Worker could not be completed: connect
 * failure, write failure, a closed/broken channel, a malformed response, or
 * no ACCEPTED within the configured timeout.
 */
public final class RuntimeIpcException extends RuntimeException {

    public RuntimeIpcException(String message) {
        super(message);
    }

    public RuntimeIpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
