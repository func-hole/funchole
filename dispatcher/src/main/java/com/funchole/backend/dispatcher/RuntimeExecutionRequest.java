package com.funchole.backend.dispatcher;

import java.util.UUID;

/**
 * One concrete execution attempt handed to a selected runtime target.
 *
 * Everything is derived from the already-pinned Invocation / Step Execution
 * state; no mutable Flow or component tables are consulted. {@code input} is
 * kept as a JSON string so it stays transport neutral for the IPC protocol.
 *
 * {@code input} is supplied explicitly by the caller rather than inferred
 * from the step's position: the flow's first step receives the root
 * Invocation input payload, and every subsequent step receives exactly the
 * previous step's stored result, passed through without transformation (a
 * mapping engine is a future concern). Which input applies is a decision the
 * Dispatcher makes when it dispatches a step - not something this record or
 * the runtime worker infers from {@code position == 1}.
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

    public static RuntimeExecutionRequest of(InvocationStepExecution stepExecution, String input) {
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
                input
        );
    }
}
