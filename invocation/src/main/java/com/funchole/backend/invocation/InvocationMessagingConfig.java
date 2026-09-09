package com.funchole.backend.invocation;

public final class InvocationMessagingConfig {

    public static final String STREAM_NAME = "FUNC_HOLE_INVOCATIONS";
    public static final String INVOCATION_READY_SUBJECT = "funchole.invocation.ready";
    public static final String DISPATCHER_DURABLE = "funchole-dispatcher";

    private InvocationMessagingConfig() {
    }
}
