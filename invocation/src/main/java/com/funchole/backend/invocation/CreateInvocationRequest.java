package com.funchole.backend.invocation;

import java.util.UUID;

public record CreateInvocationRequest(
        UUID flowId,
        String flowKey,
        UUID flowVersionId,
        String inputPayload
) {
}
