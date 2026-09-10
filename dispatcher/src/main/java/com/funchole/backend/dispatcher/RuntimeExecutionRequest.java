package com.funchole.backend.dispatcher;

import com.funchole.backend.invocation.Invocation;
import java.util.UUID;

/**
 * One concrete execution attempt handed to a selected runtime target.
 *
 * Everything is derived from the already-pinned Invocation / Step Execution
 * state; no mutable Flow or component tables are consulted. {@code input} is
 * kept as a JSON string so it stays transport neutral for the future IPC
 * protocol.
 *
 * Two factories encode the current minimal input-propagation rule:
 * <ul>
 *   <li>{@link #fromStepExecution(InvocationStepExecution, Invocation)} - for
 *       the flow's first FUNCTION step, the input is the root Invocation
 *       input payload.</li>
 *   <li>{@link #fromNextStepExecution(InvocationStepExecution, String)} - for
 *       any subsequent step, the input is exactly the previous step's stored
 *       result, passed through without transformation (do not transform or
 *       merge it; a mapping engine is a future concern).</li>
 * </ul>
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

    public static RuntimeExecutionRequest fromNextStepExecution(InvocationStepExecution stepExecution, String previousResult) {
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
                previousResult
        );
    }
}
