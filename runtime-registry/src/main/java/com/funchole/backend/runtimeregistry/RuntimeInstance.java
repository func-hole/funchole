package com.funchole.backend.runtimeregistry;

import java.util.Locale;

/**
 * A registered, fake/in-memory runtime capable of executing steps of a given
 * runtime type. {@code runtimeInstanceId} is a logical service-instance
 * identity (e.g. "runtime-node-dev-1"), not a database entity id, so it is
 * modeled as a plain String rather than a UUID.
 *
 * {@code socketPath} is the local Unix Domain Socket filesystem path the
 * runtime worker listens on. It is transport registration metadata only -
 * Runtime Registry does not open or validate the socket itself, it just
 * carries the endpoint through to the selected {@link RuntimeTarget} so the
 * Dispatcher's transport-agnostic {@code RuntimeExecutionGateway} can resolve
 * where to connect without Flow/Invocation data ever needing to know about it.
 */
public record RuntimeInstance(
        String runtimeInstanceId,
        String runtimeType,
        RuntimeInstanceStatus status,
        int capacity,
        int inFlight,
        String socketPath
) {

    public RuntimeInstance {
        if (runtimeInstanceId == null || runtimeInstanceId.isBlank()) {
            throw new IllegalArgumentException("runtimeInstanceId is required");
        }
        if (runtimeType == null || runtimeType.isBlank()) {
            throw new IllegalArgumentException("runtimeType is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        if (inFlight < 0) {
            throw new IllegalArgumentException("inFlight must not be negative");
        }
        if (socketPath == null || socketPath.isBlank()) {
            throw new IllegalArgumentException("socketPath is required");
        }
        runtimeType = runtimeType.trim().toUpperCase(Locale.ROOT);
    }
}
