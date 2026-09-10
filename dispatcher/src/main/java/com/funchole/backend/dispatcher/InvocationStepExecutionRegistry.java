package com.funchole.backend.dispatcher;

import java.util.UUID;

/**
 * Durable persistence boundary for planned {@link DispatchableStep}s.
 *
 * Implementations must guarantee that {@link #createOrGetReadyExecution} is
 * idempotent per invocation/step/attempt so that JetStream redelivery of an
 * INVOCATION_READY event never creates duplicate execution records.
 */
public interface InvocationStepExecutionRegistry {

    /**
     * Creates the durable execution record for the given planned step, or
     * returns the already-persisted record if one exists for the same
     * invocation/step/attempt identity.
     */
    InvocationStepExecution createOrGetReadyExecution(DispatchableStep dispatchableStep);

    InvocationStepExecution markRunning(UUID executionId, String runtimeInstanceId);

    InvocationStepExecutionTransition markCompleted(UUID executionId, RuntimeExecutionResult result);

    InvocationStepExecutionTransition markFailed(UUID executionId, RuntimeExecutionResult result);
}
