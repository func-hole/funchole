package com.funchole.backend.invocation;

public interface InvocationEventPublisher {

    void publishInvocationReady(Invocation invocation);

    void publishInvocationCompleted(Invocation invocation);

    void publishInvocationFailed(Invocation invocation);
}
