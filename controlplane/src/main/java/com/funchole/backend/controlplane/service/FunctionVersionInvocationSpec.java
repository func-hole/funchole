package com.funchole.backend.controlplane.service;

import java.util.UUID;

/**
 * Exactly-pinned, internally RESOLVED handoff request for a direct
 * FunctionVersion invocation. NOT caller input: every field here comes from
 * the durable FunctionVersion that was validated READY by
 * {@link FunctionVersionInvocationService} - callers can never supply or
 * override function identity or runtime metadata.
 *
 * The exact {@code functionVersionId} stays authoritative for the lifetime of
 * the invocation - no active/latest resolution and no Flow/FlowRoute lookups
 * ever happen on this path. {@code runtimeType} is copied verbatim from the
 * FunctionVersion; the actual runtime decision still belongs to the existing
 * Dispatcher / Runtime Registry execution path.
 */
public record FunctionVersionInvocationSpec(
        UUID functionId,
        String functionKey,
        UUID functionVersionId,
        String runtimeType,
        String inputPayload
) {
}
