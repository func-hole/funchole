package com.funchole.backend.runtime;

import java.util.Locale;

/**
 * Validates a decoded INVOKE message before it is accepted, including that
 * its runtime type matches the type this worker instance represents. This
 * worker never dynamically becomes a different runtime type.
 */
final class RuntimeInvokeValidator {

    private final String runtimeType;

    RuntimeInvokeValidator(String runtimeType) {
        this.runtimeType = runtimeType.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * @return a rejection reason, or {@code null} if the message is valid and
     *         compatible with this worker's runtime type
     */
    String validate(RuntimeInvokeMessage message) {
        if (message == null) {
            return "message is required";
        }
        if (!RuntimeInvokeMessage.TYPE.equals(message.type())) {
            return "unsupported message type: " + message.type();
        }
        if (message.executionId() == null) {
            return "executionId is required";
        }
        RuntimeInvokePayload payload = message.payload();
        if (payload == null) {
            return "payload is required";
        }
        if (payload.invocationId() == null) {
            return "invocationId is required";
        }
        if (payload.stepId() == null) {
            return "stepId is required";
        }
        if (payload.attempt() <= 0) {
            return "attempt must be positive";
        }
        if (payload.componentId() == null) {
            return "componentId is required";
        }
        if (payload.componentVersionId() == null) {
            return "componentVersionId is required";
        }
        if (payload.runtimeType() == null || payload.runtimeType().isBlank()) {
            return "runtimeType is required";
        }

        String requestRuntimeType = payload.runtimeType().trim().toUpperCase(Locale.ROOT);
        if (!runtimeType.equals(requestRuntimeType)) {
            return "runtime type " + requestRuntimeType + " is not compatible with this worker's runtime type " + runtimeType;
        }
        return null;
    }
}
