package com.funchole.backend.runtimeregistry;

import java.util.Optional;

/**
 * Answers "where can this execution run?" for a planned Step Execution.
 *
 * Runtime Registry knows nothing about HTTP, Gateway, Flow ordering, or
 * Sub-Flows - it only receives a {@link RuntimeRequirement} and returns a
 * reserved {@link RuntimeTarget} or fails clearly via
 * {@link NoRuntimeCapacityException}.
 */
public interface RuntimeRegistry {

    void register(RuntimeInstance runtimeInstance);

    void unregister(String runtimeInstanceId);

    /**
     * Selects a compatible, available runtime instance with spare capacity
     * and atomically reserves one execution slot on it.
     *
     * @throws NoRuntimeCapacityException if no runtime instance can satisfy
     *         the requirement right now
     */
    RuntimeTarget selectAndReserve(RuntimeRequirement requirement);

    /**
     * Releases one previously reserved execution slot on the given runtime
     * instance. A no-op if the instance is not registered.
     */
    void release(String runtimeInstanceId);

    Optional<RuntimeInstance> find(String runtimeInstanceId);
}
