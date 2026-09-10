package com.funchole.backend.dispatcher;

import com.funchole.backend.runtimeregistry.RuntimeTarget;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Fake, in-memory runtime execution gateway. Proves the handoff boundary for
 * development and tests: it validates basic request consistency, records
 * accepted handoffs in memory, and idempotently returns the existing
 * acceptance when the same executionId is handed off again.
 *
 * Local/in-memory idempotency only - there is no distributed deduplication.
 * It does not spawn processes, start containers, execute artifacts, or open
 * any IPC channel.
 */
public final class InMemoryRuntimeExecutionGateway implements RuntimeExecutionGateway {

    private final Map<UUID, RuntimeExecutionHandle> handlesByExecutionId = new ConcurrentHashMap<>();
    private final Map<UUID, RuntimeExecutionRequest> requestsByExecutionId = new ConcurrentHashMap<>();

    @Override
    public RuntimeExecutionHandle handoff(RuntimeTarget target, RuntimeExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Runtime execution request is required");
        }
        if (request.executionId() == null) {
            return rejected(null, "executionId is required");
        }

        RuntimeExecutionHandle existing = handlesByExecutionId.get(request.executionId());
        if (existing != null) {
            return existing;
        }

        String rejectionReason = validate(target, request);
        if (rejectionReason != null) {
            return rejected(request.executionId(), rejectionReason);
        }

        RuntimeExecutionHandle handle = new RuntimeExecutionHandle(
                RuntimeExecutionAcceptance.accept(request.executionId()),
                CompletableFuture.completedFuture(RuntimeExecutionResult.success(
                        request.executionId(),
                        "{\"ok\":true,\"executionId\":\"" + request.executionId() + "\"}"
                ))
        );
        RuntimeExecutionHandle winner = handlesByExecutionId.putIfAbsent(request.executionId(), handle);
        if (winner != null) {
            return winner;
        }
        requestsByExecutionId.put(request.executionId(), request);
        return handle;
    }

    public Optional<RuntimeExecutionRequest> acceptedRequest(UUID executionId) {
        return Optional.ofNullable(requestsByExecutionId.get(executionId));
    }

    public int acceptedCount() {
        return requestsByExecutionId.size();
    }

    private String validate(RuntimeTarget target, RuntimeExecutionRequest request) {
        if (target == null) {
            return "Runtime target is required";
        }
        if (request.invocationId() == null) {
            return "invocationId is required";
        }
        if (request.stepId() == null) {
            return "stepId is required";
        }
        if (request.attempt() <= 0) {
            return "attempt must be positive";
        }
        if (request.componentId() == null) {
            return "componentId is required";
        }
        if (request.componentVersionId() == null) {
            return "componentVersionId is required";
        }
        if (request.runtimeType() == null || request.runtimeType().isBlank()) {
            return "runtimeType is required";
        }

        String targetRuntimeType = target.runtimeType() == null
                ? ""
                : target.runtimeType().trim().toUpperCase(Locale.ROOT);
        if (targetRuntimeType.isBlank()) {
            return "Runtime target runtimeType is required";
        }
        if (!targetRuntimeType.equals(request.runtimeType().trim().toUpperCase(Locale.ROOT))) {
            return "Runtime target " + target.runtimeInstanceId() + " with runtime type " + target.runtimeType()
                    + " is not compatible with request runtime type " + request.runtimeType();
        }
        return null;
    }

    private RuntimeExecutionHandle rejected(UUID executionId, String reason) {
        return new RuntimeExecutionHandle(
                RuntimeExecutionAcceptance.reject(executionId, reason),
                CompletableFuture.failedFuture(new IllegalStateException(reason))
        );
    }
}
