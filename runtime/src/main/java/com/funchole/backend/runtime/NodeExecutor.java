package com.funchole.backend.runtime;

import java.util.concurrent.CompletionStage;

/**
 * Executes JavaScript artifacts through a persistent, warm Node process. An
 * implementation must not spawn a new process per execution.
 */
public interface NodeExecutor {

    CompletionStage<NodeExecutionResult> execute(NodeExecutionRequest request);
}
