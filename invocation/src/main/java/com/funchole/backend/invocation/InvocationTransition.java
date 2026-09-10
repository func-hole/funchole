package com.funchole.backend.invocation;

/**
 * Result of an idempotent terminal-state transition attempt. {@code
 * transitioned} is {@code false} when the Invocation was already in that
 * terminal state (e.g. a duplicate completion), so callers know not to
 * re-publish a completion event.
 */
public record InvocationTransition(
        Invocation invocation,
        boolean transitioned
) {
}
