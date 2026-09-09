package com.funchole.backend.invocation;

public final class NoopInvocationEventPublisher implements InvocationEventPublisher {

    @Override
    public void publishInvocationReady(Invocation invocation) {
    }
}
