package com.funchole.backend.invocation;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Transport-neutral, read-only view of one exact Invocation's durable state,
 * as produced by {@link InvocationInspectionService}. Carries no HTTP/MCP/
 * CLI-specific type, so the same result can back a Web/API, CLI, or MCP
 * adapter later without change.
 *
 * <p>Exactly one of the two identity groups below is populated, depending on
 * how the invocation was created - both are stored in the same underlying
 * {@code invocations} row, reused for either shape:
 * <ul>
 *   <li>{@code flowId}/{@code flowKey}/{@code flowVersionId} - a normal Flow
 *       invocation.</li>
 *   <li>{@code functionVersionId} - a direct FunctionVersion invocation; this
 *       is the exact FunctionVersion pinned at invocation creation, never
 *       re-resolved against the active/latest version.</li>
 * </ul>
 */
public record InvocationInspection(
        UUID invocationId,
        InvocationStatus status,
        UUID flowId,
        String flowKey,
        UUID flowVersionId,
        UUID functionVersionId,
        String inputPayload,
        String result,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt
) {
}
