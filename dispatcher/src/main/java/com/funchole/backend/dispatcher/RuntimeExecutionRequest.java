package com.funchole.backend.dispatcher;

import com.funchole.backend.invocation.Invocation;
import java.util.UUID;

/**
 * One concrete execution attempt handed to a selected runtime target.
 *
 * Everything is derived from the already-pinned Invocation / Step Execution
 * state; no mutable Flow or component tables are consulted. {@code input}
 * carries the root Invocation input payload (persisted JSON) because only the
 * first step of the flow is planned so far - step-to-step input mapping is a
 * future concern. The value is kept as a JSON string so it stays transport
 * neutral for the future IPC protocol.
 */
public record RuntimeExecutionRequest(
        UUID executionId,
        UUID invocationId,
        UUID flowId,
        UUID flowVersionId,
        UUID stepId,
        int attempt,
        String componentType,
        UUID componentId,
        UUID componentVersionId,
        String runtimeType,
        String input
) {

    public static RuntimeExecutionRequest fromStepExecution(InvocationStepExecution stepExecution, Invocation invocation) {
        return new RuntimeExecutionRequest(
                stepExecution.id(),
                stepExecution.invocationId(),
                stepExecution.flowId(),
                stepExecution.flowVersionId(),
                stepExecution.stepId(),
                stepExecution.attempt(),
                stepExecution.componentType(),
                stepExecution.componentId(),
                stepExecution.componentVersionId(),
                stepExecution.runtimeType(),
                invocation.inputPayload()
        );
    }
}
