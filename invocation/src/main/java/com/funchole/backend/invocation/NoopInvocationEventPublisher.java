package com.funchole.backend.invocation;

public final class NoopInvocationEventPublisher implements InvocationEventPublisher {

    @Override
    public void publishInvocationReady(Invocation invocation) {
    }

    @Override
    public void publishInvocationCompleted(Invocation invocation) {
    }

    @Override
    public void publishInvocationFailed(Invocation invocation) {
    }
}
