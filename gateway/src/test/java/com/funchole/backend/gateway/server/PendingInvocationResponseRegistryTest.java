package com.funchole.backend.gateway.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PendingInvocationResponseRegistryTest {

    private ScheduledExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void completingAResolvesTheCorrectPendingEntryOnly() throws Exception {
        PendingInvocationResponseRegistry registry = registry(Duration.ofSeconds(10));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<PendingInvocationResponseRegistry.PendingOutcome> firstOutcomes = new CopyOnWriteArrayList<>();
        List<PendingInvocationResponseRegistry.PendingOutcome> secondOutcomes = new CopyOnWriteArrayList<>();
        registry.register(first, firstOutcomes::add);
        registry.register(second, secondOutcomes::add);

        registry.complete(first);

        assertEquals(1, firstOutcomes.size());
        assertEquals(PendingInvocationResponseRegistry.PendingOutcome.COMPLETED, firstOutcomes.getFirst());
        assertTrue(secondOutcomes.isEmpty());
        assertEquals(1, registry.pendingCount());
    }

    @Test
    void timesOutAndCleansUpWhenNeverCompleted() throws Exception {
        PendingInvocationResponseRegistry registry = registry(Duration.ofMillis(50));
        UUID invocationId = UUID.randomUUID();
        List<PendingInvocationResponseRegistry.PendingOutcome> outcomes = new CopyOnWriteArrayList<>();

        registry.register(invocationId, outcomes::add);
        awaitCondition(() -> !outcomes.isEmpty(), Duration.ofSeconds(2));

        assertEquals(1, outcomes.size());
        assertEquals(PendingInvocationResponseRegistry.PendingOutcome.TIMED_OUT, outcomes.getFirst());
        assertEquals(0, registry.pendingCount());
    }

    @Test
    void cancelRemovesThePendingEntryWithoutInvokingTheCallback() throws Exception {
        PendingInvocationResponseRegistry registry = registry(Duration.ofSeconds(10));
        UUID invocationId = UUID.randomUUID();
        List<PendingInvocationResponseRegistry.PendingOutcome> outcomes = new CopyOnWriteArrayList<>();
        registry.register(invocationId, outcomes::add);

        registry.cancel(invocationId);

        assertEquals(0, registry.pendingCount());
        assertTrue(outcomes.isEmpty());
        // A late completion for a cancelled invocation is a safe no-op.
        registry.complete(invocationId);
        assertTrue(outcomes.isEmpty());
    }

    @Test
    void duplicateCompletionInvokesTheCallbackExactlyOnce() throws Exception {
        PendingInvocationResponseRegistry registry = registry(Duration.ofSeconds(10));
        UUID invocationId = UUID.randomUUID();
        List<PendingInvocationResponseRegistry.PendingOutcome> outcomes = new CopyOnWriteArrayList<>();
        registry.register(invocationId, outcomes::add);

        registry.complete(invocationId);
        registry.complete(invocationId);

        assertEquals(1, outcomes.size());
    }

    private PendingInvocationResponseRegistry registry(Duration timeout) {
        executor = Executors.newSingleThreadScheduledExecutor();
        return new PendingInvocationResponseRegistry(executor, timeout);
    }

    private void awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadlineMillis) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }
}
