package com.funchole.backend.controlplane.service;

/**
 * Outbound control-plane port: hands a fully-resolved, exactly-pinned
 * FunctionVersion invocation to the invocation/execution infrastructure.
 *
 * The control-plane does not depend on (and does not know) the invocation
 * module's persistence or messaging types: a port adapter is composed at the
 * application boundary (the same way Dispatcher and Runtime components are
 * wired by configuration). The adapter is responsible for turning this
 * transport-neutral request into an invocation on the existing invocation /
 * dispatcher / runtime execution path, returning the invocation id and its
 * initial durable status.
 */
public interface FunctionVersionInvocationHandoff {

    DirectInvocationResult dispatch(FunctionVersionInvocationSpec spec);

    /** Control-plane-facing adapter failure: dispatch could not create/queue the invocation. */
    class FunctionVersionInvocationDispatchException extends RuntimeException {
        public FunctionVersionInvocationDispatchException(String message, Throwable cause) {
            super(message, cause);
        }

        public FunctionVersionInvocationDispatchException(String message) {
            super(message);
        }
    }
}
