package com.funchole.backend.dispatcher;

import java.util.List;

public record InvocationValidationResult(
        boolean valid,
        List<String> errors,
        List<String> warnings
) {
    public static InvocationValidationResult success() {
        return new InvocationValidationResult(true, List.of(), List.of());
    }

    public static InvocationValidationResult invalid(List<String> errors) {
        return new InvocationValidationResult(false, List.copyOf(errors), List.of());
    }
}
