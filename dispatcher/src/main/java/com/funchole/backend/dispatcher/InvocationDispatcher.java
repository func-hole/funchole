package com.funchole.backend.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationMessagingConfig;
import com.funchole.backend.invocation.InvocationReadyEvent;
import com.funchole.backend.invocation.InvocationRegistry;
import com.funchole.backend.invocation.InvocationSnapshot;
import com.funchole.backend.invocation.InvocationStatus;
import com.funchole.backend.runtimeregistry.RuntimeInstance;
import com.funchole.backend.runtimeregistry.RuntimeRegistry;
import com.funchole.backend.runtimeregistry.RuntimeRequirement;
import com.funchole.backend.runtimeregistry.RuntimeTarget;
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
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InvocationDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(InvocationDispatcher.class);
    private static final int FIRST_STEP_POSITION = 1;

    private final Connection connection;
    private final InvocationRegistry invocationRegistry;
    private final InvocationStepExecutionRegistry stepExecutionRegistry;
    private final RuntimeRegistry runtimeRegistry;
    private final ObjectMapper objectMapper;
    private final InvocationSnapshotValidator snapshotValidator;
    private final ExecutionPlanner executionPlanner;
    private final RuntimeExecutionGateway executionGateway;
    private final JetStreamSubscription subscription;

    /**
     * Terminal RESULT/ERROR messages are correlated and completed on the IPC
     * transport's own reader thread (see {@link IpcRuntimeExecutionGateway}).
     * That thread must stay free to keep decoding/correlating frames for
     * other in-flight executions, so the blocking JDBC terminal-state
     * transition (and the runtime capacity release that follows it) is
     * dispatched onto this small, bounded, dedicated pool instead of running
     * inline on whichever thread completes the completion future.
     */
    private final ExecutorService completionExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "dispatcher-completion");
        thread.setDaemon(true);
        return thread;
    });

    public InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            RuntimeRegistry runtimeRegistry
    ) {
        this(connection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                new ExecutionPlanner(), new InMemoryRuntimeExecutionGateway());
    }

    InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            RuntimeRegistry runtimeRegistry,
            ExecutionPlanner executionPlanner
    ) {
        this(connection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                executionPlanner, new InMemoryRuntimeExecutionGateway());
    }

    InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            RuntimeRegistry runtimeRegistry,
            ExecutionPlanner executionPlanner,
            RuntimeExecutionGateway executionGateway
    ) {
        this.connection = connection;
        this.invocationRegistry = invocationRegistry;
        this.stepExecutionRegistry = stepExecutionRegistry;
        this.runtimeRegistry = runtimeRegistry;
        this.objectMapper = new ObjectMapper();
        this.snapshotValidator = new InvocationSnapshotValidator();
        this.executionPlanner = executionPlanner;
        this.executionGateway = executionGateway;
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

        dispatchStepExecution(invocation, stepExecution, null);
    }

    /**
     * The single dispatch path shared by the initial planned step and every
     * sequentially progressed step: reserve runtime capacity, build the
     * execution request, hand off to the runtime worker, persist RUNNING on
     * acceptance, and register terminal-completion handling.
     *
     * {@code stepInput} is the request input override; {@code null} means the
     * step is the first step and receives the root Invocation input payload.
     */
    private void dispatchStepExecution(Invocation invocation, InvocationStepExecution stepExecution, String stepInput) {
        RuntimeRequirement runtimeRequirement = new RuntimeRequirement(stepExecution.runtimeType());
        RuntimeTarget runtimeTarget = runtimeRegistry.selectAndReserve(runtimeRequirement);
        logger.info(
                "Runtime selected: executionId={}, invocationId={}, stepId={}, runtimeInstanceId={}, runtimeType={}",
                stepExecution.id(),
                stepExecution.invocationId(),
                stepExecution.stepId(),
                runtimeTarget.runtimeInstanceId(),
                runtimeTarget.runtimeType()
        );
        try {
            RuntimeExecutionRequest executionRequest = stepExecution.position() == FIRST_STEP_POSITION
                    ? RuntimeExecutionRequest.fromStepExecution(stepExecution, invocation)
                    : RuntimeExecutionRequest.fromNextStepExecution(stepExecution, stepInput);
            RuntimeExecutionHandle handle = executionGateway.handoff(runtimeTarget, executionRequest);
            RuntimeExecutionAcceptance acceptance = handle.acceptance();
            if (!acceptance.accepted()) {
                throw new IllegalStateException("Runtime execution handoff rejected: " + acceptance.rejectionReason());
            }
            stepExecution = stepExecutionRegistry.markRunning(stepExecution.id(), runtimeTarget.runtimeInstanceId());
            registerTerminalCompletion(stepExecution, runtimeTarget, handle);
        } catch (RuntimeException handoffFailure) {
            runtimeRegistry.release(runtimeTarget.runtimeInstanceId());
            throw handoffFailure;
        }

        RuntimeInstance selectedInstance = runtimeRegistry.find(runtimeTarget.runtimeInstanceId()).orElse(null);
        logger.info(
                "Runtime execution accepted: executionId={}, invocationId={}, stepId={}, componentId={}, componentVersionId={}, runtimeInstanceId={}, runtimeType={}, attempt={}, status={}, inFlight={}, capacity={}",
                stepExecution.id(),
                stepExecution.invocationId(),
                stepExecution.stepId(),
                stepExecution.componentId(),
                stepExecution.componentVersionId(),
                runtimeTarget.runtimeInstanceId(),
                runtimeTarget.runtimeType(),
                stepExecution.attempt(),
                stepExecution.status(),
                selectedInstance == null ? "?" : selectedInstance.inFlight(),
                selectedInstance == null ? "?" : selectedInstance.capacity()
        );
    }

    private void registerTerminalCompletion(
            InvocationStepExecution stepExecution,
            RuntimeTarget runtimeTarget,
            RuntimeExecutionHandle handle
    ) {
        handle.completion().whenCompleteAsync((result, failure) -> {
            if (failure != null) {
                logger.warn(
                        "Runtime completion failed before terminal message: executionId={}, runtimeInstanceId={}, message={}",
                        stepExecution.id(),
                        runtimeTarget.runtimeInstanceId(),
                        failure.getMessage()
                );
                return;
            }
            handleTerminalResult(result, runtimeTarget);
        }, completionExecutor);
    }

    private void handleTerminalResult(RuntimeExecutionResult result, RuntimeTarget runtimeTarget) {
        try {
            InvocationStepExecutionTransition transition = switch (result.terminalType()) {
                case RESULT -> stepExecutionRegistry.markCompleted(result.executionId(), result);
                case ERROR -> stepExecutionRegistry.markFailed(result.executionId(), result);
            };
            if (transition.transitioned()) {
                runtimeRegistry.release(runtimeTarget.runtimeInstanceId());
            }
            InvocationStepExecution execution = transition.execution();
            if (execution.status() == InvocationStepExecutionStatus.COMPLETED) {
                logger.info(
                        "Runtime execution completed: executionId={}, status={}, runtimeInstanceId={}, capacityReleased={}",
                        execution.id(),
                        execution.status(),
                        runtimeTarget.runtimeInstanceId(),
                        transition.transitioned()
                );
            } else {
                logger.info(
                        "Runtime execution failed: executionId={}, status={}, errorCode={}, runtimeInstanceId={}, capacityReleased={}",
                        execution.id(),
                        execution.status(),
                        result.error() == null ? "UNKNOWN" : result.error().code(),
                        runtimeTarget.runtimeInstanceId(),
                        transition.transitioned()
                );
            }
            if (transition.transitioned() && execution.status() == InvocationStepExecutionStatus.COMPLETED) {
                planAndDispatchNextStep(execution);
            }
        } catch (RuntimeException exception) {
            logger.warn(
                    "Runtime terminal persistence failed: executionId={}, runtimeInstanceId={}, message={}",
                    result.executionId(),
                    runtimeTarget.runtimeInstanceId(),
                    exception.getMessage()
            );
        }
    }

    /**
     * After a FUNCTION step completes durably, finds the next ordered step of
     * the same flow from the frozen Invocation snapshot and dispatches it with
     * the previous step's stored result as its input. This milestone stops
     * progression when there is no further FUNCTION step; Invocation
     * completion and final HTTP responses are future work.
     */
    private void planAndDispatchNextStep(InvocationStepExecution completedExecution) {
        Invocation invocation = invocationRegistry
                .findById(completedExecution.invocationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot progress flow: invocation not found: " + completedExecution.invocationId()));
        InvocationSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(invocation.dependencySnapshot(), InvocationSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot progress flow: invalid snapshot for invocation "
                    + completedExecution.invocationId(), exception);
        }

        Optional<DispatchableStep> nextStep =
                executionPlanner.planNextStep(invocation, snapshot, completedExecution.position());
        if (nextStep.isEmpty()) {
            logger.info(
                    "Flow progression stopped: no further FUNCTION step after position={} for invocationId={}, flowKey={}",
                    completedExecution.position(),
                    invocation.invocationId(),
                    invocation.flowKey()
            );
            return;
        }

        InvocationStepExecution nextExecution = stepExecutionRegistry.createOrGetReadyExecution(nextStep.get());
        if (nextExecution.status() != InvocationStepExecutionStatus.READY) {
            logger.info(
                    "Next step execution {} is already {} - skipping duplicate progression dispatch for stepId={}, attempt={}",
                    nextExecution.id(),
                    nextExecution.status(),
                    nextExecution.stepId(),
                    nextExecution.attempt()
            );
            return;
        }
        logger.info(
                "Next step planned: executionId={}, invocationId={}, stepId={}, position={}, componentType={}, componentId={}, componentVersionId={}, attempt={}, status={}",
                nextExecution.id(),
                nextExecution.invocationId(),
                nextExecution.stepId(),
                nextExecution.position(),
                nextExecution.componentType(),
                nextExecution.componentId(),
                nextExecution.componentVersionId(),
                nextExecution.attempt(),
                nextExecution.status()
        );

        dispatchStepExecution(invocation, nextExecution, completedExecution.result());
    }

    /**
     * Stops accepting new terminal completions. Intended for orderly
     * Dispatcher shutdown; safe to skip since the pool only holds daemon
     * threads.
     */
    public void close() {
        completionExecutor.shutdown();
        try {
            completionExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
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
