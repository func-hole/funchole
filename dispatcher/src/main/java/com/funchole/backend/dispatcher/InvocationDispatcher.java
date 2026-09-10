package com.funchole.backend.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationMessagingConfig;
import com.funchole.backend.invocation.InvocationReadyEvent;
import com.funchole.backend.invocation.InvocationRegistry;
import com.funchole.backend.invocation.InvocationSnapshot;
import com.funchole.backend.invocation.InvocationStatus;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InvocationDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(InvocationDispatcher.class);

    private final Connection connection;
    private final InvocationRegistry invocationRegistry;
    private final InvocationStepExecutionRegistry stepExecutionRegistry;
    private final ObjectMapper objectMapper;
    private final InvocationSnapshotValidator snapshotValidator;
    private final ExecutionPlanner executionPlanner;
    private final JetStreamSubscription subscription;

    public InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry
    ) {
        this(connection, invocationRegistry, stepExecutionRegistry, new ExecutionPlanner());
    }

    InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            ExecutionPlanner executionPlanner
    ) {
        this.connection = connection;
        this.invocationRegistry = invocationRegistry;
        this.stepExecutionRegistry = stepExecutionRegistry;
        this.objectMapper = new ObjectMapper();
        this.snapshotValidator = new InvocationSnapshotValidator();
        this.executionPlanner = executionPlanner;
        ensureStream();
        this.subscription = subscribe();
    }

    public boolean processNext(Duration timeout) {
        try {
            List<Message> messages = subscription.fetch(1, timeout);
            if (messages.isEmpty()) {
                return false;
            }

            Message message = messages.getFirst();
            process(message);
            message.ack();
            return true;
        } catch (Exception exception) {
            logger.warn("Dispatcher failed to process invocation-ready message: {}", exception.getMessage());
            return false;
        }
    }

    private void process(Message message) throws IOException {
        InvocationReadyEvent event = objectMapper.readValue(
                new String(message.getData(), StandardCharsets.UTF_8),
                InvocationReadyEvent.class
        );
        if (!InvocationReadyEvent.EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalStateException("Unsupported invocation event type: " + event.eventType());
        }

        Invocation invocation = invocationRegistry
                .findById(event.invocationId())
                .orElseThrow(() -> new IllegalStateException("Invocation not found: " + event.invocationId()));
        if (invocation.status() != InvocationStatus.PENDING) {
            throw new IllegalStateException("Invocation is not PENDING: " + invocation.invocationId());
        }

        InvocationSnapshot snapshot = objectMapper.readValue(invocation.dependencySnapshot(), InvocationSnapshot.class);
        InvocationValidationResult validationResult = snapshotValidator.validate(snapshot);
        if (!validationResult.valid()) {
            throw new IllegalStateException("Invocation snapshot is not executable-shaped: " + validationResult.errors());
        }

        DispatchableStep dispatchableStep = executionPlanner.planInitialStep(invocation, snapshot);
        logger.info(
                "Invocation planned: invocationId={}, flowKey={}, stepId={}, stepKey={}, position={}, componentType={}, componentId={}, componentVersionId={}",
                dispatchableStep.invocationId(),
                invocation.flowKey(),
                dispatchableStep.stepId(),
                dispatchableStep.stepKey(),
                dispatchableStep.position(),
                dispatchableStep.componentType(),
                dispatchableStep.componentId(),
                dispatchableStep.componentVersionId()
        );

        InvocationStepExecution stepExecution = stepExecutionRegistry.createOrGetReadyExecution(dispatchableStep);
        if (stepExecution.status() != InvocationStepExecutionStatus.READY) {
            throw new IllegalStateException("Step execution is not READY: " + stepExecution.id());
        }
        logger.info(
                "Step execution ready: executionId={}, invocationId={}, stepId={}, position={}, componentId={}, componentVersionId={}, attempt={}, status={}",
                stepExecution.id(),
                stepExecution.invocationId(),
                stepExecution.stepId(),
                stepExecution.position(),
                stepExecution.componentId(),
                stepExecution.componentVersionId(),
                stepExecution.attempt(),
                stepExecution.status()
        );
    }

    private JetStreamSubscription subscribe() {
        try {
            JetStream jetStream = connection.jetStream();
            PullSubscribeOptions options = PullSubscribeOptions.builder()
                    .stream(InvocationMessagingConfig.STREAM_NAME)
                    .durable(InvocationMessagingConfig.DISPATCHER_DURABLE)
                    .build();
            JetStreamSubscription jetStreamSubscription = jetStream.subscribe(
                    InvocationMessagingConfig.INVOCATION_READY_SUBJECT,
                    options
            );
            connection.flush(Duration.ofSeconds(5));
            return jetStreamSubscription;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to subscribe to invocation-ready events", exception);
        } catch (IOException | JetStreamApiException | TimeoutException exception) {
            throw new IllegalStateException("Failed to subscribe to invocation-ready events", exception);
        }
    }

    private void ensureStream() {
        try {
            var management = connection.jetStreamManagement();
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
            throw new IllegalStateException("Failed to ensure invocation JetStream stream", exception);
        }
    }
}
