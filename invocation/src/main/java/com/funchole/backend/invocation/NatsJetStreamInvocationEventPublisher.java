package com.funchole.backend.invocation;

import com.fasterxml.jackson.core.JsonProcessingException;
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
        try {
            JetStream jetStream = connection.jetStream();
            byte[] payload = objectMapper.writeValueAsBytes(InvocationReadyEvent.from(invocation));
            jetStream.publish(InvocationMessagingConfig.INVOCATION_READY_SUBJECT, payload);
        } catch (IOException | JetStreamApiException exception) {
            throw new InvocationPublishException("Failed to publish INVOCATION_READY event", exception);
        }
    }

    private void ensureStream() {
        try {
            JetStreamManagement management = connection.jetStreamManagement();
            try {
                management.getStreamInfo(InvocationMessagingConfig.STREAM_NAME);
            } catch (JetStreamApiException exception) {
                StreamConfiguration configuration = StreamConfiguration.builder()
                        .name(InvocationMessagingConfig.STREAM_NAME)
                        .subjects(InvocationMessagingConfig.INVOCATION_READY_SUBJECT)
                        .storageType(StorageType.File)
                        .build();
                management.addStream(configuration);
            }
        } catch (IOException | JetStreamApiException exception) {
            throw new InvocationPublishException("Failed to ensure invocation JetStream stream", exception);
        }
    }
}
