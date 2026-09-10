package com.funchole.backend.invocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.io.IOException;

public final class NatsJetStreamInvocationEventPublisher implements InvocationEventPublisher {

    private final Connection connection;
    private final ObjectMapper objectMapper;

    public NatsJetStreamInvocationEventPublisher(Connection connection) {
        this.connection = connection;
        this.objectMapper = new ObjectMapper();
        ensureStream();
    }

    @Override
    public void publishInvocationReady(Invocation invocation) {
        publish(InvocationMessagingConfig.INVOCATION_READY_SUBJECT, InvocationReadyEvent.from(invocation));
    }

    @Override
    public void publishInvocationCompleted(Invocation invocation) {
        publish(InvocationMessagingConfig.INVOCATION_TERMINAL_SUBJECT, InvocationTerminalEvent.completed(invocation));
    }

    @Override
    public void publishInvocationFailed(Invocation invocation) {
        publish(InvocationMessagingConfig.INVOCATION_TERMINAL_SUBJECT, InvocationTerminalEvent.failed(invocation));
    }

    private void publish(String subject, Object event) {
        try {
            JetStream jetStream = connection.jetStream();
            byte[] payload = objectMapper.writeValueAsBytes(event);
            jetStream.publish(subject, payload);
        } catch (IOException | JetStreamApiException exception) {
            throw new InvocationPublishException("Failed to publish event on subject " + subject, exception);
        }
    }

    private void ensureStream() {
        try {
            JetStreamManagement management = connection.jetStreamManagement();
            StreamConfiguration configuration = StreamConfiguration.builder()
                    .name(InvocationMessagingConfig.STREAM_NAME)
                    .subjects(
                            InvocationMessagingConfig.INVOCATION_READY_SUBJECT,
                            InvocationMessagingConfig.INVOCATION_TERMINAL_SUBJECT
                    )
                    .storageType(StorageType.File)
                    .build();
            try {
                management.getStreamInfo(InvocationMessagingConfig.STREAM_NAME);
                management.updateStream(configuration);
            } catch (JetStreamApiException exception) {
                management.addStream(configuration);
            }
        } catch (IOException | JetStreamApiException exception) {
            throw new InvocationPublishException("Failed to ensure invocation JetStream stream", exception);
        }
    }
}
