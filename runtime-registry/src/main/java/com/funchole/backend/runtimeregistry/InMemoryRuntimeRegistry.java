package com.funchole.backend.runtimeregistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory Runtime Registry. Registrations and capacity reservations only
 * live in this process's heap - there is no persistence and no distributed
 * coordination.
 *
 * Known limitation: because reservations are in-memory only, a Dispatcher
 * restart or a JetStream redelivery observed by a different Dispatcher
 * process will not see capacity reserved by a prior attempt. Solving durable/
 * distributed runtime reservation is explicitly out of scope for this
 * milestone.
 */
public final class InMemoryRuntimeRegistry implements RuntimeRegistry {

    private final Map<String, MutableRuntimeInstance> runtimeInstances = new LinkedHashMap<>();

    @Override
    public synchronized void register(RuntimeInstance runtimeInstance) {
        runtimeInstances.put(runtimeInstance.runtimeInstanceId(), new MutableRuntimeInstance(runtimeInstance));
    }

    @Override
    public synchronized void unregister(String runtimeInstanceId) {
        runtimeInstances.remove(runtimeInstanceId);
    }

    @Override
    public synchronized RuntimeTarget selectAndReserve(RuntimeRequirement requirement) {
        String requiredType = requirement.runtimeType();
        boolean anyCompatible = false;
        MutableRuntimeInstance selected = null;

        for (MutableRuntimeInstance candidate : runtimeInstances.values()) {
            if (!candidate.runtimeType.equals(requiredType)) {
                continue;
            }
            anyCompatible = true;
            if (candidate.status != RuntimeInstanceStatus.AVAILABLE) {
                continue;
            }
            if (candidate.inFlight >= candidate.capacity) {
                continue;
            }
            if (selected == null
                    || candidate.inFlight < selected.inFlight
                    || (candidate.inFlight == selected.inFlight
                        && candidate.runtimeInstanceId.compareTo(selected.runtimeInstanceId) < 0)) {
                selected = candidate;
            }
        }

        if (selected == null) {
            if (!anyCompatible) {
                throw new NoRuntimeCapacityException("No compatible runtime registered for runtime type: " + requiredType);
            }
            throw new NoRuntimeCapacityException("No available runtime capacity for runtime type: " + requiredType);
        }

        selected.inFlight++;
        return new RuntimeTarget(selected.runtimeInstanceId, selected.runtimeType, selected.socketPath);
    }

    @Override
    public synchronized void release(String runtimeInstanceId) {
        MutableRuntimeInstance instance = runtimeInstances.get(runtimeInstanceId);
        if (instance == null) {
            return;
        }
        if (instance.inFlight > 0) {
            instance.inFlight--;
        }
    }

    @Override
    public synchronized Optional<RuntimeInstance> find(String runtimeInstanceId) {
        MutableRuntimeInstance instance = runtimeInstances.get(runtimeInstanceId);
        return instance == null ? Optional.empty() : Optional.of(instance.toSnapshot());
    }

    private static final class MutableRuntimeInstance {
        private final String runtimeInstanceId;
        private final String runtimeType;
        private final RuntimeInstanceStatus status;
        private final int capacity;
        private final String socketPath;
        private int inFlight;

        private MutableRuntimeInstance(RuntimeInstance runtimeInstance) {
            this.runtimeInstanceId = runtimeInstance.runtimeInstanceId();
            this.runtimeType = runtimeInstance.runtimeType();
            this.status = runtimeInstance.status();
            this.capacity = runtimeInstance.capacity();
            this.inFlight = runtimeInstance.inFlight();
            this.socketPath = runtimeInstance.socketPath();
        }

        private RuntimeInstance toSnapshot() {
            return new RuntimeInstance(runtimeInstanceId, runtimeType, status, capacity, inFlight, socketPath);
        }
    }
}
