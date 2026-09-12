package com.funchole.backend.invocation;

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
 * {@link InvocationRegistry} - no HTTP, MCP, or CLI type appears anywhere
 * in this class, so a Web/API, CLI, or MCP adapter can call it directly
 * later.
 *
 * <p>Which identity fields the result carries is decided purely by the
 * durable {@link InvocationKind} column - never by inspecting
 * {@code dependencySnapshot} shape, which is execution data, not identity.
 */
public class InvocationInspectionService {

    private final InvocationRegistry invocationRegistry;

    public InvocationInspectionService(InvocationRegistry invocationRegistry) {
        this.invocationRegistry = invocationRegistry;
    }

    public InvocationInspection inspect(UUID invocationId) {
        if (invocationId == null) {
            throw new IllegalArgumentException("invocationId is required");
        }
        Invocation invocation = invocationRegistry.findById(invocationId)
                .orElseThrow(() -> new IllegalStateException("Invocation not found: " + invocationId));

        return switch (invocation.kind()) {
            case DIRECT_FUNCTION -> new InvocationInspection(
                    invocation.invocationId(),
                    invocation.status(),
                    null,
                    null,
                    null,
                    invocation.functionVersionId(),
                    invocation.inputPayload(),
                    invocation.result(),
                    invocation.error(),
                    invocation.createdAt(),
                    invocation.updatedAt(),
                    invocation.completedAt()
            );
            case FLOW -> new InvocationInspection(
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
        };
    }
}
