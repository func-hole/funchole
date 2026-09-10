package com.funchole.backend.runtimeregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InMemoryRuntimeRegistryTest {

    private final InMemoryRuntimeRegistry registry = new InMemoryRuntimeRegistry();

    @Test
    void registeredRuntimeIsDiscoverableForMatchingRequirement() {
        registry.register(new RuntimeInstance("runtime-a", "NODE", RuntimeInstanceStatus.AVAILABLE, 4, 0));

        RuntimeTarget target = registry.selectAndReserve(new RuntimeRequirement("NODE"));

        assertEquals("runtime-a", target.runtimeInstanceId());
        assertEquals("NODE", target.runtimeType());
    }

    @Test
    void selectsOnlyCompatibleRuntimeType() {
        registry.register(new RuntimeInstance("runtime-node", "NODE", RuntimeInstanceStatus.AVAILABLE, 4, 0));
        registry.register(new RuntimeInstance("runtime-python", "PYTHON", RuntimeInstanceStatus.AVAILABLE, 4, 0));

        RuntimeTarget target = registry.selectAndReserve(new RuntimeRequirement("NODE"));

        assertEquals("runtime-node", target.runtimeInstanceId());
    }

    @Test
    void selectsRuntimeWithSpareCapacityOverFullRuntime() {
        registry.register(new RuntimeInstance("runtime-a", "NODE", RuntimeInstanceStatus.AVAILABLE, 2, 2));
        registry.register(new RuntimeInstance("runtime-b", "NODE", RuntimeInstanceStatus.AVAILABLE, 2, 1));

        RuntimeTarget target = registry.selectAndReserve(new RuntimeRequirement("NODE"));

        assertEquals("runtime-b", target.runtimeInstanceId());
    }

    @Test
    void selectsLeastInFlightAmongMultipleCandidates() {
        registry.register(new RuntimeInstance("runtime-a", "NODE", RuntimeInstanceStatus.AVAILABLE, 10, 3));
        registry.register(new RuntimeInstance("runtime-b", "NODE", RuntimeInstanceStatus.AVAILABLE, 10, 1));
        registry.register(new RuntimeInstance("runtime-c", "NODE", RuntimeInstanceStatus.AVAILABLE, 10, 2));

        RuntimeTarget target = registry.selectAndReserve(new RuntimeRequirement("NODE"));

        assertEquals("runtime-b", target.runtimeInstanceId());
    }

    @Test
    void tieBreaksDeterministicallyByRuntimeInstanceId() {
        registry.register(new RuntimeInstance("runtime-b", "NODE", RuntimeInstanceStatus.AVAILABLE, 10, 1));
        registry.register(new RuntimeInstance("runtime-a", "NODE", RuntimeInstanceStatus.AVAILABLE, 10, 1));

        RuntimeTarget target = registry.selectAndReserve(new RuntimeRequirement("NODE"));

        assertEquals("runtime-a", target.runtimeInstanceId());
    }

    @Test
    void reservesCapacityOnSelectionAndBecomesIneligibleWhenFull() {
        registry.register(new RuntimeInstance("runtime-a", "NODE", RuntimeInstanceStatus.AVAILABLE, 2, 1));

        registry.selectAndReserve(new RuntimeRequirement("NODE"));

        assertEquals(2, registry.find("runtime-a").orElseThrow().inFlight());
        assertThrows(NoRuntimeCapacityException.class, () -> registry.selectAndReserve(new RuntimeRequirement("NODE")));
    }

    @Test
    void releaseFreesUpAReservedSlot() {
        registry.register(new RuntimeInstance("runtime-a", "NODE", RuntimeInstanceStatus.AVAILABLE, 2, 2));

        registry.release("runtime-a");

        assertEquals(1, registry.find("runtime-a").orElseThrow().inFlight());
        RuntimeTarget target = registry.selectAndReserve(new RuntimeRequirement("NODE"));
        assertEquals("runtime-a", target.runtimeInstanceId());
        assertEquals(2, registry.find("runtime-a").orElseThrow().inFlight());
    }

    @Test
    void failsClearlyWhenNoCompatibleRuntimeIsRegistered() {
        registry.register(new RuntimeInstance("runtime-python", "PYTHON", RuntimeInstanceStatus.AVAILABLE, 4, 0));

        NoRuntimeCapacityException exception = assertThrows(
                NoRuntimeCapacityException.class,
                () -> registry.selectAndReserve(new RuntimeRequirement("NODE"))
        );
        assertTrue(exception.getMessage().contains("No compatible runtime"));
    }

    @Test
    void failsClearlyWhenCompatibleRuntimeHasNoCapacity() {
        registry.register(new RuntimeInstance("runtime-a", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 1));

        NoRuntimeCapacityException exception = assertThrows(
                NoRuntimeCapacityException.class,
                () -> registry.selectAndReserve(new RuntimeRequirement("NODE"))
        );
        assertTrue(exception.getMessage().contains("No available runtime capacity"));
    }

    @Test
    void unregisterRemovesRuntimeFromSelection() {
        registry.register(new RuntimeInstance("runtime-a", "NODE", RuntimeInstanceStatus.AVAILABLE, 4, 0));

        registry.unregister("runtime-a");

        assertThrows(NoRuntimeCapacityException.class, () -> registry.selectAndReserve(new RuntimeRequirement("NODE")));
    }
}
