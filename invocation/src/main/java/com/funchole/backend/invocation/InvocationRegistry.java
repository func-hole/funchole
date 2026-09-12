package com.funchole.backend.invocation;

import java.util.Optional;
import java.util.UUID;

public interface InvocationRegistry {

    Invocation create(CreateInvocationRequest request);

    /**
     * Transport-neutral direct FunctionVersion invocation. The exact
     * functionVersionId is pinned into the immutable snapshot up front; no
     * Flow/route resolution or active/latest lookup happens on this path.
     * Publishes the same INVOCATION_READY event so the existing Dispatcher
     * / Runtime execution path picks the invocation up unchanged.
     */
    Invocation createDirectInvocation(DirectInvocationRequest request);

    Optional<Invocation> findById(UUID invocationId);

    /**
     * Idempotently marks the Invocation COMPLETED with the given final
     * response (already-durable RESPONSE step output) and publishes
     * INVOCATION_COMPLETED - but only on the transition that actually makes
     * it terminal.
     */
    InvocationTransition markCompleted(UUID invocationId, String result);

    /**
     * Idempotently marks the Invocation FAILED with the given failure detail
     * and publishes INVOCATION_FAILED - but only on the transition that
     * actually makes it terminal.
     */
    InvocationTransition markFailed(UUID invocationId, String error);
}
