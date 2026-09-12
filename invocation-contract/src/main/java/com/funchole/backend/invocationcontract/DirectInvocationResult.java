package com.funchole.backend.invocationcontract;

import java.util.UUID;

/**
 * Transport-neutral, cross-module result of a direct FunctionVersion
 * invocation: the invocation id and its initial durable status (for later
 * inspection). Never the persisted Invocation record itself - only the
 * minimal fields a caller across the boundary needs. No synchronous
 * execution completion is represented here.
 */
public record DirectInvocationResult(
        UUID invocationId,
        UUID functionVersionId,
        String initialStatus
) {
}
