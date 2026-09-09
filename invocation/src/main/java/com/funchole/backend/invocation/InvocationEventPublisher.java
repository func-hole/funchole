package com.funchole.backend.invocation;

public interface InvocationEventPublisher {

    void publishInvocationReady(Invocation invocation);
}
