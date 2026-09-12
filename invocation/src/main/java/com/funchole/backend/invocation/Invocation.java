package com.funchole.backend.invocation;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A durable Invocation. Exactly one identity group is populated, keyed by
 * {@link InvocationKind}:
 * <ul>
 *   <li>{@link InvocationKind#FLOW} - {@code flowId}/{@code flowKey}/
 *       {@code flowVersionId} IDENTIFIED; function identity fields are
 *       {@code null}.</li>
 *   <li>{@link InvocationKind#DIRECT_FUNCTION} - {@code functionId}/
 *       {@code functionKey}/{@code functionVersionId} populated; flow
 *       identity is {@code null}. The pinned {@code functionVersionId} is
 *       the exact FunctionVersion captured at invocation creation and is
 *       never re-resolved against active/latest versions.</li>
 * </ul>
 */
public record Invocation(
        UUID invocationId,
        InvocationKind kind,
        UUID flowId,
        String flowKey,
        UUID flowVersionId,
        UUID functionId,
        String functionKey,
        UUID functionVersionId,
        InvocationStatus status,
        String inputPayload,
        String dependencySnapshot,
        String result,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt
) {
}
