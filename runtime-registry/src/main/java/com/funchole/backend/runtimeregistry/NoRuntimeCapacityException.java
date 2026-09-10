package com.funchole.backend.runtimeregistry;

/**
 * Thrown when no registered runtime can satisfy a {@link RuntimeRequirement},
 * either because no compatible runtime type is registered or because every
 * compatible runtime is at capacity.
 */
public final class NoRuntimeCapacityException extends RuntimeException {

    public NoRuntimeCapacityException(String message) {
        super(message);
    }
}
