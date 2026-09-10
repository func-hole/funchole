package com.funchole.backend.invocation;

import java.util.UUID;

/**
 * Published only after the Invocation's terminal state is durably persisted.
 * Carries just {@code invocationId} - the final response/error is already
 * durable in the Invocation Registry, so consumers (the Gateway) re-fetch it
 * rather than trusting an in-flight event payload.
 */
public record InvocationTerminalEvent(
        String eventType,
        UUID invocationId
) {

    public static final String COMPLETED_EVENT_TYPE = "INVOCATION_COMPLETED";
    public static final String FAILED_EVENT_TYPE = "INVOCATION_FAILED";

    public static InvocationTerminalEvent completed(Invocation invocation) {
        return new InvocationTerminalEvent(COMPLETED_EVENT_TYPE, invocation.invocationId());
    }

    public static InvocationTerminalEvent failed(Invocation invocation) {
        return new InvocationTerminalEvent(FAILED_EVENT_TYPE, invocation.invocationId());
    }
}
