package com.funchole.backend.dispatcher;

public record RuntimeExecutionError(
        String code,
        String message
) {
}
