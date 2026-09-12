package com.funchole.backend.controlplane.service;

import java.util.UUID;

/**
 * Exactly-pinned request for a direct FunctionVersion invocation, owned by
 * the control-plane boundary. The exact {@code functionVersionId} is
 * authoritative for the whole invocation - no active/latest resolution and
 * no Flow/FlowRoute lookups happen on this path.
 *
 * {@code runtimeType} is not selected by this control-plane side either; it
 * is copied verbatim from the invoked FunctionVersion so the existing
 * Dispatcher / Runtime Registry execution path performs the actual runtime
 * selection.
 */
public record FunctionVersionInvocationSpec(
        UUID functionId,
        String functionKey,
        UUID functionVersionId,
        String runtimeType,
        String inputPayload
) {
}
