package com.funchole.backend.invocation;

/**
 * Explicit boundary for handing a fully-resolved, exactly-pinned direct
 * FunctionVersion execution request to the Invocation subsystem. Callers
 * (e.g. controlplane) depend only on this narrow contract - never on
 * {@link InvocationRegistry}, {@link JdbcInvocationRegistry}, {@link Invocation}
 * persistence records, the Dispatcher, or the Runtime Registry.
 *
 * <p>The Invocation subsystem owns everything past this point: durable
 * Invocation creation ({@link InvocationKind#DIRECT_FUNCTION}), the immutable
 * execution snapshot, the initial PENDING state, and ready-event publication
 * onto the existing Dispatcher/Runtime execution path - the same path every
 * other Invocation already goes through. No second execution engine.
 */
public interface FunctionVersionInvocationHandoff {

    DirectInvocationResult dispatch(DirectInvocationRequest request);

    /** Boundary-facing failure: dispatch could not create/queue the invocation. */
    class FunctionVersionInvocationDispatchException extends RuntimeException {
        public FunctionVersionInvocationDispatchException(String message, Throwable cause) {
            super(message, cause);
        }

        public FunctionVersionInvocationDispatchException(String message) {
            super(message);
        }
    }
}
