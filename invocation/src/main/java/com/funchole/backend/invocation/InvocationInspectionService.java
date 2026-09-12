package com.funchole.backend.invocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;

/**
 * Transport-neutral application service for inspecting one exact durable
 * Invocation. A single read against {@link InvocationRegistry} - the same
 * authoritative source of truth the Dispatcher and Runtime execution path
 * write through - never Gateway in-memory pending state, temporary
 * Dispatcher state, or Runtime Worker process-local state.
 *
 * <p>Performs no waiting, polling, or re-execution: one call, one durable
 * read, one {@link InvocationInspection}. Depends only on
 * {@link InvocationRegistry} and plain JSON parsing of already-persisted
 * data - no HTTP, MCP, or CLI type appears anywhere in this class, so a
 * Web/API, CLI, or MCP adapter can call it directly later.
 */
public class InvocationInspectionService {

    private final InvocationRegistry invocationRegistry;
    private final ObjectMapper objectMapper;

    public InvocationInspectionService(InvocationRegistry invocationRegistry) {
        this.invocationRegistry = invocationRegistry;
        this.objectMapper = new ObjectMapper();
    }

    public InvocationInspection inspect(UUID invocationId) {
        if (invocationId == null) {
            throw new IllegalArgumentException("invocationId is required");
        }
        Invocation invocation = invocationRegistry.findById(invocationId)
                .orElseThrow(() -> new IllegalStateException("Invocation not found: " + invocationId));

        if (isDirectFunctionVersionInvocation(invocation)) {
            return new InvocationInspection(
                    invocation.invocationId(),
                    invocation.status(),
                    null,
                    null,
                    null,
                    invocation.flowVersionId(),
                    invocation.inputPayload(),
                    invocation.result(),
                    invocation.error(),
                    invocation.createdAt(),
                    invocation.updatedAt(),
                    invocation.completedAt()
            );
        }

        return new InvocationInspection(
                invocation.invocationId(),
                invocation.status(),
                invocation.flowId(),
                invocation.flowKey(),
                invocation.flowVersionId(),
                null,
                invocation.inputPayload(),
                invocation.result(),
                invocation.error(),
                invocation.createdAt(),
                invocation.updatedAt(),
                invocation.completedAt()
        );
    }

    /**
     * A direct FunctionVersion invocation and a normal Flow invocation are
     * persisted through the identical {@code invocations} row shape (see
     * {@link JdbcInvocationRegistry#createDirectInvocation}), so the only
     * durable signal telling them apart is the dependency snapshot's shape:
     * exactly one flow with exactly one step keyed
     * {@link DirectInvocationRequest#DIRECT_INVOCATION_STEP_KEY}.
     */
    private boolean isDirectFunctionVersionInvocation(Invocation invocation) {
        if (invocation.dependencySnapshot() == null) {
            return false;
        }
        InvocationSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(invocation.dependencySnapshot(), InvocationSnapshot.class);
        } catch (Exception exception) {
            return false;
        }
        List<InvocationFlowSnapshot> flows = snapshot.flows();
        if (flows == null || flows.size() != 1) {
            return false;
        }
        List<InvocationStepSnapshot> steps = flows.get(0).steps();
        return steps != null
                && steps.size() == 1
                && DirectInvocationRequest.DIRECT_INVOCATION_STEP_KEY.equals(steps.get(0).stepKey());
    }
}
