package com.funchole.backend.dispatcher;

import java.util.UUID;

/**
 * Acknowledgement of the execution HANDOFF only - not of Function completion.
 *
 * {@code accepted} means the selected runtime took responsibility for the
 * execution attempt. Execution result/output arrives later through the
 * runtime result milestone.
 */
public record RuntimeExecutionAcceptance(
        boolean accepted,
        UUID executionId,
        String rejectionReason
) {

    public static RuntimeExecutionAcceptance accept(UUID executionId) {
        return new RuntimeExecutionAcceptance(true, executionId, null);
    }

    public static RuntimeExecutionAcceptance reject(UUID executionId, String rejectionReason) {
        return new RuntimeExecutionAcceptance(false, executionId, rejectionReason);
    }
}
