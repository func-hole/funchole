package com.funchole.backend.controlplane.service;

import java.util.UUID;

/**
 * Transport-neutral result of a direct FunctionVersion invocation: the invocation id and
 * its initial status (as the durable invocation status name) for later inspection. No
 * synchronous execution completion is represented here.
 */
public record DirectInvocationResult(
        UUID invocationId,
        UUID functionVersionId,
        String initialStatus
) {
}
