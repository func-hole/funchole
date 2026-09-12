package com.funchole.backend.invocation;

/**
 * Durable, persisted discriminator for how an Invocation was created - never
 * inferred from {@code dependencySnapshot} shape, which remains execution
 * data only.
 */
public enum InvocationKind {
    FLOW,
    DIRECT_FUNCTION
}
