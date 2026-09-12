package com.funchole.backend.invocation;

import java.util.UUID;

/**
 * Request for a transport-neutral direct FunctionVersion invocation: the
 * exact function version is pinned up front and stays authoritative for the
 * lifetime of the invocation - no active/latest resolution and no Flow/Flow
 * version lookups ever happen on this path.
 *
 * The function identity is carried neutrally (it becomes the snapshot's flow
 * identity and the invoked step's pinned component), while {@code
 * runtimeType} is NOT selected here - the caller copies it verbatim from the
 * FunctionVersion, and the actual runtime decision still belongs to the
 * Dispatcher / Runtime Registry execution path.
 */
public record DirectInvocationRequest(
        UUID functionId,
        String functionKey,
        UUID functionVersionId,
        String runtimeType,
        String inputPayload
) {

    /**
     * The single step key {@link JdbcInvocationRegistry} writes into the
     * dependency snapshot of every direct FunctionVersion invocation - the
     * only signal that later distinguishes such an invocation from a normal
     * Flow invocation, since both are persisted through the exact same
     * {@code invocations} row shape.
     */
    public static final String DIRECT_INVOCATION_STEP_KEY = "invoke-function";
}
