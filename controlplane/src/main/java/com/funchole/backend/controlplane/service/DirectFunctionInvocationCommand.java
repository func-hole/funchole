package com.funchole.backend.controlplane.service;

import java.util.UUID;

/**
 * Caller-facing input for a direct FunctionVersion invocation. Callers supply
 * ONLY the exact functionVersionId and the invocation input - function
 * identity and runtime metadata are resolved from the durable FunctionVersion
 * and can never be overridden from outside.
 */
public record DirectFunctionInvocationCommand(
        UUID functionVersionId,
        String inputPayload
) {
}
