package com.funchole.backend.invocationcontract;

import java.util.UUID;

/**
 * Transport-neutral, cross-module request for a direct FunctionVersion
 * invocation: the exact function version is pinned up front and stays
 * authoritative for the lifetime of the invocation - no active/latest
 * resolution and no Flow/Flow version lookups ever happen on this path.
 *
 * <p>Every field here is resolved from durable state before this record is
 * constructed - the caller (controlplane) never supplies function identity
 * or runtime metadata directly; only {@code functionVersionId} and
 * {@code inputPayload} ever originate from outside the system.
 */
public record DirectInvocationRequest(
        UUID functionId,
        String functionKey,
        UUID functionVersionId,
        String runtimeType,
        String inputPayload
) {
}
