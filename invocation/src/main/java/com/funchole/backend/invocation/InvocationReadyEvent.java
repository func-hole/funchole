package com.funchole.backend.invocation;

import java.util.UUID;

public record InvocationReadyEvent(
        String eventType,
        UUID invocationId
) {

    public static final String EVENT_TYPE = "INVOCATION_READY";

    public static InvocationReadyEvent from(Invocation invocation) {
        return new InvocationReadyEvent(EVENT_TYPE, invocation.invocationId());
    }
}
