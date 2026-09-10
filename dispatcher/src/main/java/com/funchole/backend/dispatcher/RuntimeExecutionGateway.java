package com.funchole.backend.dispatcher;

import com.funchole.backend.runtimeregistry.RuntimeTarget;

/**
 * Hands an execution request to the runtime infrastructure selected by the
 * Runtime Registry.
 *
 * The gateway owns the handoff only. It knows nothing about Flow order,
 * planning, or step progression; the request arrives already complete. It
 * does not wait for execution completion and answers only whether the
 * selected target accepted the execution.
 *
 * {@link InMemoryRuntimeExecutionGateway} remains for tests/dev fakes.
 * {@link IpcRuntimeExecutionGateway} is the real local transport: a
 * persistent Unix Domain Socket connection to a Runtime Worker process.
 */
public interface RuntimeExecutionGateway {

    RuntimeExecutionAcceptance handoff(RuntimeTarget target, RuntimeExecutionRequest request);
}
