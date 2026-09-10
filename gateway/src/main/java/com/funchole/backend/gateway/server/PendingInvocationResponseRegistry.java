package com.funchole.backend.gateway.server;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Thread-safe in-memory correlation between an invocationId and the HTTP
 * request waiting for its terminal response.
 *
 * Deliberately minimal: no persistence, no distributed correlation, no
 * generic session framework. An entry is removed exactly once - on
 * completion, on timeout, or on client disconnect (see
 * {@link #cancel(UUID)}) - whichever happens first wins; the others become
 * no-ops. A Gateway restart loses all pending correlation, which is
 * acceptable since the underlying HTTP connections are gone too.
 */
public final class PendingInvocationResponseRegistry {

    private final ConcurrentHashMap<UUID, PendingEntry> pendingByInvocationId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor;
    private final Duration timeout;

    public PendingInvocationResponseRegistry(ScheduledExecutorService timeoutExecutor, Duration timeout) {
        this.timeoutExecutor = timeoutExecutor;
        this.timeout = timeout;
    }

    /**
     * Registers a pending completion for {@code invocationId}. {@code
     * onOutcome} is invoked exactly once, from whichever thread first
     * resolves the entry (the NATS completion listener thread, or this
     * registry's own timeout executor thread) - callers must not assume it
     * runs on any particular thread.
     */
    public void register(UUID invocationId, Consumer<PendingOutcome> onOutcome) {
        PendingEntry entry = new PendingEntry(onOutcome);
        if (pendingByInvocationId.putIfAbsent(invocationId, entry) != null) {
            return;
        }
        entry.timeoutTask = timeoutExecutor.schedule(
                () -> resolve(invocationId, PendingOutcome.TIMED_OUT), timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Called when the matching Invocation reaches a terminal state. */
    public void complete(UUID invocationId) {
        resolve(invocationId, PendingOutcome.COMPLETED);
    }

    /** Called when the client disconnects before completion/timeout - removes the entry silently. */
    public void cancel(UUID invocationId) {
        PendingEntry entry = pendingByInvocationId.remove(invocationId);
        if (entry != null && entry.timeoutTask != null) {
            entry.timeoutTask.cancel(false);
        }
    }

    public int pendingCount() {
        return pendingByInvocationId.size();
    }

    private void resolve(UUID invocationId, PendingOutcome outcome) {
        PendingEntry entry = pendingByInvocationId.remove(invocationId);
        if (entry == null) {
            return;
        }
        if (entry.timeoutTask != null) {
            entry.timeoutTask.cancel(false);
        }
        entry.onOutcome.accept(outcome);
    }

    public enum PendingOutcome {
        COMPLETED,
        TIMED_OUT
    }

    private static final class PendingEntry {
        private final Consumer<PendingOutcome> onOutcome;
        private volatile ScheduledFuture<?> timeoutTask;

        private PendingEntry(Consumer<PendingOutcome> onOutcome) {
            this.onOutcome = onOutcome;
        }
    }
}
