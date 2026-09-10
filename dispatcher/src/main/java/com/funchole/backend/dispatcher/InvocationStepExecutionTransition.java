package com.funchole.backend.dispatcher;

public record InvocationStepExecutionTransition(
        InvocationStepExecution execution,
        boolean transitioned
) {
}
