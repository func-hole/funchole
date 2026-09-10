package com.funchole.backend.dispatcher;

import java.util.concurrent.CompletionStage;

public record RuntimeExecutionHandle(
        RuntimeExecutionAcceptance acceptance,
        CompletionStage<RuntimeExecutionResult> completion
) {
}
