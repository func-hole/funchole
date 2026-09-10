package com.funchole.backend.gateway.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.invocation.InvocationMessagingConfig;
import com.funchole.backend.invocation.InvocationTerminalEvent;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges INVOCATION_COMPLETED/INVOCATION_FAILED events to the Gateway's
 * in-memory pending-HTTP-response correlation.
 *
 * Uses a plain core NATS subscription rather than a durable JetStream
 * consumer: the Dispatcher/Invocation Registry side still publishes through
 * JetStream (so the event is durable there), but the Gateway has no need for
 * redelivery - a pending HTTP request that outlives a Gateway restart is
 * already gone anyway, since {@link PendingInvocationResponseRegistry} is
 * in-memory only.
 */
public final class GatewayInvocationCompletionListener implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GatewayInvocationCompletionListener.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Dispatcher dispatcher;

    public GatewayInvocationCompletionListener(Connection connection, PendingInvocationResponseRegistry pendingRegistry) {
        this.dispatcher = connection.createDispatcher(message -> handleMessage(message.getData(), pendingRegistry));
        dispatcher.subscribe(InvocationMessagingConfig.INVOCATION_TERMINAL_SUBJECT);
    }

    private void handleMessage(byte[] data, PendingInvocationResponseRegistry pendingRegistry) {
        try {
            InvocationTerminalEvent event = objectMapper.readValue(data, InvocationTerminalEvent.class);
            if (event.invocationId() == null) {
                logger.warn("Discarding invocation terminal event with no invocationId");
                return;
            }
            pendingRegistry.complete(event.invocationId());
        } catch (Exception exception) {
            logger.warn("Failed to process invocation terminal event: {}", exception.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            dispatcher.unsubscribe(InvocationMessagingConfig.INVOCATION_TERMINAL_SUBJECT);
        } catch (RuntimeException ignored) {
            // Best-effort unsubscribe on shutdown.
        }
    }
}
