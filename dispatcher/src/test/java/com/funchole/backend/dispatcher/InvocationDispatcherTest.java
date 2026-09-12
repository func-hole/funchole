package com.funchole.backend.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.invocation.CreateInvocationRequest;
import com.funchole.backend.invocation.DirectInvocationRequest;
import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationMessagingConfig;
import com.funchole.backend.invocation.InvocationRegistry;
import com.funchole.backend.invocation.InvocationSnapshot;
import com.funchole.backend.invocation.InvocationStatus;
import com.funchole.backend.invocation.InvocationTransition;
import com.funchole.backend.invocation.JdbcInvocationRegistry;
import com.funchole.backend.invocation.NatsJetStreamInvocationEventPublisher;
import com.funchole.backend.runtimeregistry.InMemoryRuntimeRegistry;
import com.funchole.backend.runtimeregistry.RuntimeInstance;
import com.funchole.backend.runtimeregistry.RuntimeInstanceStatus;
import com.funchole.backend.runtimeregistry.RuntimeRegistry;
import com.funchole.backend.runtimeregistry.RuntimeRequirement;
import com.funchole.backend.runtimeregistry.RuntimeTarget;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class InvocationDispatcherTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("funchole")
            .withUsername("funchole")
            .withPassword("funchole");

    @Container
    private static final GenericContainer<?> nats = new GenericContainer<>(DockerImageName.parse("nats:2.14.6-alpine"))
            .withExposedPorts(4222)
            .withCommand("-js", "-sd", "/tmp/nats/jetstream");

    private static final String DEV_RUNTIME_INSTANCE_ID = "runtime-node-dev-1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Connection natsConnection;
    private JdbcInvocationRegistry invocationRegistry;
    private JdbcInvocationStepExecutionRegistry stepExecutionRegistry;
    private InMemoryRuntimeRegistry runtimeRegistry;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        try (var connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists flow_steps");
            statement.execute("drop table if exists flow_versions");
            statement.execute("drop table if exists flows");
            statement.execute("drop table if exists invocations");
            statement.execute("""
                    create table flows (
                        id UUID primary key,
                        app_user_id UUID not null,
                        gateway_id UUID not null,
                        active_flow_version_id UUID,
                        active_flow_version_status VARCHAR(100),
                        flow_key VARCHAR(150) not null,
                        name VARCHAR(255) not null,
                        description TEXT,
                        http_method VARCHAR(50) not null,
                        path VARCHAR(2048) not null,
                        priority INTEGER not null default 100,
                        created_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        deleted_at TIMESTAMP WITH TIME ZONE,
                        constraint uk_flows_flow_key unique (flow_key)
                    )
                    """);
            statement.execute("""
                    create table flow_versions (
                        id UUID primary key,
                        flow_id UUID not null,
                        version INTEGER not null,
                        status VARCHAR(100) not null default 'DRAFT',
                        runtime VARCHAR(100) not null default 'NODE',
                        metadata JSONB,
                        created_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        adopted_at TIMESTAMP WITH TIME ZONE,
                        archived_at TIMESTAMP WITH TIME ZONE,
                        constraint uk_flow_versions_flow_id_version unique (flow_id, version),
                        constraint uk_flow_versions_flow_id_id unique (flow_id, id),
                        constraint uk_flow_versions_id_status unique (id, status)
                    )
                    """);
            statement.execute("""
                    create table flow_steps (
                        id UUID primary key,
                        flow_version_id UUID not null,
                        step_key VARCHAR(150) not null,
                        component_type VARCHAR(100) not null,
                        position INTEGER not null,
                        component_id UUID not null,
                        component_version_id UUID not null,
                        metadata JSONB,
                        created_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        constraint uk_flow_steps_version_step_key unique (flow_version_id, step_key),
                        constraint uk_flow_steps_version_position unique (flow_version_id, position)
                    )
                    """);
            statement.execute("""
                    create table invocations (
                        id UUID primary key,
                        kind VARCHAR(50) not null,
                        flow_id UUID,
                        flow_key VARCHAR(150),
                        flow_version_id UUID,
                        function_id UUID,
                        function_key VARCHAR(255),
                        function_version_id UUID,
                        status VARCHAR(100) not null,
                        input_payload JSONB,
                        dependency_snapshot JSONB,
                        result JSONB,
                        error JSONB,
                        created_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        completed_at TIMESTAMP WITH TIME ZONE
                    )
                    """);
            statement.execute("drop table if exists invocation_step_executions");
            statement.execute("""
                    create table invocation_step_executions (
                        id UUID primary key,
                        invocation_id UUID not null,
                        flow_id UUID not null,
                        flow_version_id UUID not null,
                        step_id UUID not null,
                        position INTEGER not null,
                        component_type VARCHAR(100) not null,
                        component_id UUID not null,
                        component_version_id UUID not null,
                        runtime_type VARCHAR(100) not null default 'NODE',
                        runtime_instance_id VARCHAR(255),
                        status VARCHAR(100) not null,
                        attempt INTEGER not null default 1,
                        result JSONB,
                        error JSONB,
                        created_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        started_at TIMESTAMP WITH TIME ZONE,
                        completed_at TIMESTAMP WITH TIME ZONE,
                        constraint uk_invocation_step_executions_invocation_step_attempt unique (invocation_id, step_id, attempt)
                    )
                    """);
        }

        natsConnection = Nats.connect("nats://" + nats.getHost() + ":" + nats.getMappedPort(4222));
        resetInvocationStream();
        invocationRegistry = new JdbcInvocationRegistry(
                dataSource,
                new NatsJetStreamInvocationEventPublisher(natsConnection)
        );
        stepExecutionRegistry = new JdbcInvocationStepExecutionRegistry(dataSource);
        runtimeRegistry = new InMemoryRuntimeRegistry();
        runtimeRegistry.register(new RuntimeInstance(
                DEV_RUNTIME_INSTANCE_ID, "NODE", RuntimeInstanceStatus.AVAILABLE, 4, 0, "/tmp/test-dev-runtime.sock"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (natsConnection != null) {
            natsConnection.close();
        }
    }

    @Test
    void consumesInvocationReadyLoadsSnapshotAndAcks() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000101");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000101");
        insertFlow(flowId, "flw_dispatch", flowVersionId, 1);
        insertStep(
                flowVersionId,
                "function-a",
                "FUNCTION",
                1,
                UUID.fromString("30000000-0000-0000-0000-000000000101"),
                UUID.fromString("40000000-0000-0000-0000-000000000101")
        );
        insertStep(
                flowVersionId,
                "fetch-orders",
                "FUNCTION",
                2,
                UUID.fromString("30000000-0000-0000-0000-000000000102"),
                UUID.fromString("40000000-0000-0000-0000-000000000102")
        );
        insertStep(
                flowVersionId,
                "build-orders-response",
                "FUNCTION",
                3,
                UUID.fromString("30000000-0000-0000-0000-000000000103"),
                UUID.fromString("40000000-0000-0000-0000-000000000103")
        );
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_dispatch",
                flowVersionId,
                "{\"path\":\"/dispatch\"}"
        ));
        InvocationDispatcher dispatcher = new InvocationDispatcher(natsConnection, invocationRegistry, stepExecutionRegistry, runtimeRegistry);

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));
        assertEquals(invocation.invocationId(), invocationRegistry.findById(invocation.invocationId()).orElseThrow().invocationId());
        assertFalse(dispatcher.processNext(Duration.ofMillis(500)));
    }

    @Test
    void doesNotTreatInvalidSnapshotAsSuccessfulDispatchPreparation() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000111");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000111");
        insertFlow(flowId, "flw_without_steps", flowVersionId, 1);
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_without_steps",
                flowVersionId,
                "{\"path\":\"/invalid\"}"
        ));
        InvocationDispatcher dispatcher = new InvocationDispatcher(natsConnection, invocationRegistry, stepExecutionRegistry, runtimeRegistry);

        assertFalse(dispatcher.processNext(Duration.ofSeconds(5)));
        assertEquals(invocation.invocationId(), invocationRegistry.findById(invocation.invocationId()).orElseThrow().invocationId());
    }

    @Test
    void doesNotAckAsSuccessWhenInvocationCannotBeLoaded() throws Exception {
        InvocationDispatcher dispatcher = new InvocationDispatcher(natsConnection, new MissingInvocationRegistry(), stepExecutionRegistry, runtimeRegistry);
        natsConnection.jetStream().publish(
                InvocationMessagingConfig.INVOCATION_READY_SUBJECT,
                """
                        {"eventType":"INVOCATION_READY","invocationId":"99999999-9999-9999-9999-999999999999"}
                        """.getBytes()
        );

        assertFalse(dispatcher.processNext(Duration.ofSeconds(5)));
    }

    @Test
    void plansFirstDispatchableStepAndAcks() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000121");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000121");
        UUID firstComponentId = UUID.fromString("30000000-0000-0000-0000-000000000121");
        UUID firstComponentVersionId = UUID.fromString("40000000-0000-0000-0000-000000000121");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 1, firstComponentId, firstComponentVersionId);
        insertStep(
                flowVersionId,
                "fetch-orders",
                "FUNCTION",
                2,
                UUID.fromString("30000000-0000-0000-0000-000000000122"),
                UUID.fromString("40000000-0000-0000-0000-000000000122")
        );
        insertStep(
                flowVersionId,
                "build-orders-response",
                "RESPONSE",
                3,
                UUID.fromString("30000000-0000-0000-0000-000000000123"),
                UUID.fromString("40000000-0000-0000-0000-000000000123")
        );
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_orders_list",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        RecordingExecutionPlanner planner = new RecordingExecutionPlanner();
        InvocationDispatcher dispatcher = new InvocationDispatcher(natsConnection, invocationRegistry, stepExecutionRegistry, runtimeRegistry, planner);

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

        assertTrue(planner.planned());
        DispatchableStep step = planner.plannedStep();
        assertEquals(invocation.invocationId(), step.invocationId());
        assertEquals(flowId, step.flowId());
        assertEquals(flowVersionId, step.flowVersionId());
        assertEquals(1, step.position());
        assertEquals("FUNCTION", step.componentType());
        assertEquals(firstComponentId, step.componentId());
        assertEquals(firstComponentVersionId, step.componentVersionId());
        assertEquals("NODE", step.runtimeType());
        assertFalse(dispatcher.processNext(Duration.ofMillis(500)));

        awaitCondition(
                () -> stepExecutionRegistry.createOrGetReadyExecution(step).status() == InvocationStepExecutionStatus.COMPLETED,
                Duration.ofSeconds(5)
        );
        InvocationStepExecution execution = stepExecutionRegistry.createOrGetReadyExecution(step);
        assertEquals(InvocationStepExecutionStatus.COMPLETED, execution.status());
        assertEquals(1, execution.attempt());
        assertEquals(invocation.invocationId(), execution.invocationId());
        assertEquals(flowId, execution.flowId());
        assertEquals(flowVersionId, execution.flowVersionId());
        assertEquals(step.stepId(), execution.stepId());
        assertEquals(firstComponentId, execution.componentId());
        assertEquals(firstComponentVersionId, execution.componentVersionId());
        assertEquals("NODE", execution.runtimeType());

        // Full synchronous flow: FUNCTION step 2 is dispatched and completed
        // with the first step's result as its input, then the RESPONSE step
        // at position 3 executes inline (no runtime capacity) and the
        // Invocation reaches COMPLETED with the RESPONSE step's output.
        awaitCondition(() -> runtimeRegistry.find(DEV_RUNTIME_INSTANCE_ID).orElseThrow().inFlight() == 0, Duration.ofSeconds(5));
        awaitCondition(() -> countStepExecutionsUnchecked() == 3, Duration.ofSeconds(5));
        assertEquals(3, countStepExecutions());
        assertEquals(0, runtimeRegistry.find(DEV_RUNTIME_INSTANCE_ID).orElseThrow().inFlight());

        awaitCondition(
                () -> invocationRegistry.findById(invocation.invocationId()).orElseThrow().status() == InvocationStatus.COMPLETED,
                Duration.ofSeconds(5)
        );
        Invocation completedInvocation = invocationRegistry.findById(invocation.invocationId()).orElseThrow();
        assertEquals(InvocationStatus.COMPLETED, completedInvocation.status());
        assertNotNull(completedInvocation.completedAt());

        InvocationStepExecution secondExecution = stepExecutionAtPosition(flowVersionId, 2).orElseThrow();
        InvocationStepExecution responseExecution = stepExecutionAtPosition(flowVersionId, 3).orElseThrow();
        assertEquals(InvocationStepExecutionStatus.COMPLETED, responseExecution.status());
        assertEquals("RESPONSE", responseExecution.componentType());
        assertJsonEquals(responseExecution.result(), completedInvocation.result());

        JsonNode responseNode = OBJECT_MAPPER.readTree(completedInvocation.result());
        assertEquals(200, responseNode.at("/status").asInt());
        assertJsonEquals(secondExecution.result(), OBJECT_MAPPER.writeValueAsString(responseNode.at("/body")));
    }

    @Test
    void doesNotAckWhenRuntimeRegistryHasNoCapacity() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000151");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000151");
        insertFlow(flowId, "flw_no_runtime_capacity", flowVersionId, 1);
        insertStep(
                flowVersionId,
                "validate-orders-request",
                "FUNCTION",
                1,
                UUID.fromString("30000000-0000-0000-0000-000000000151"),
                UUID.fromString("40000000-0000-0000-0000-000000000151")
        );
        invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_no_runtime_capacity",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        InMemoryRuntimeRegistry emptyRuntimeRegistry = new InMemoryRuntimeRegistry();
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, emptyRuntimeRegistry
        );

        assertFalse(dispatcher.processNext(Duration.ofSeconds(5)));

        assertEquals(1, countStepExecutions());
    }

    @Test
    void doesNotAckWhenStepExecutionPersistenceFails() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000141");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000141");
        insertFlow(flowId, "flw_execution_failure", flowVersionId, 1);
        insertStep(
                flowVersionId,
                "validate-orders-request",
                "FUNCTION",
                1,
                UUID.fromString("30000000-0000-0000-0000-000000000141"),
                UUID.fromString("40000000-0000-0000-0000-000000000141")
        );
        invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_execution_failure",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection,
                invocationRegistry,
                new FailingInvocationStepExecutionRegistry(),
                runtimeRegistry
        );

        assertFalse(dispatcher.processNext(Duration.ofSeconds(5)));
        assertEquals(0, countStepExecutions());
    }

    @Test
    void doesNotAckWhenFirstStepIsNotExecutable() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000131");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000131");
        insertFlow(flowId, "flw_middleware_first", flowVersionId, 1);
        insertStep(
                flowVersionId,
                "log-request",
                "MIDDLEWARE",
                1,
                UUID.fromString("30000000-0000-0000-0000-000000000131"),
                UUID.fromString("40000000-0000-0000-0000-000000000131")
        );
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_middleware_first",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        InvocationDispatcher dispatcher = new InvocationDispatcher(natsConnection, invocationRegistry, stepExecutionRegistry, runtimeRegistry);

        assertFalse(dispatcher.processNext(Duration.ofSeconds(5)));
        assertEquals(InvocationStatus.PENDING, invocationRegistry.findById(invocation.invocationId()).orElseThrow().status());
    }

    @Test
    void handsOffPinnedExecutionRequestToSelectedRuntimeAndAcks() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000161");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000161");
        UUID componentId = UUID.fromString("30000000-0000-0000-0000-000000000161");
        UUID componentVersionId = UUID.fromString("40000000-0000-0000-0000-000000000161");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 1, componentId, componentVersionId);
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_orders_list",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        CapturingRuntimeExecutionGateway gateway = new CapturingRuntimeExecutionGateway();
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                new ExecutionPlanner(), gateway
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

        RuntimeExecutionRequest request = gateway.capturedRequest();
        RuntimeTarget target = gateway.capturedTarget();
        assertEquals(invocation.invocationId(), request.invocationId());
        assertEquals(flowId, request.flowId());
        assertEquals(flowVersionId, request.flowVersionId());
        assertEquals(componentId, request.componentId());
        assertEquals(componentVersionId, request.componentVersionId());
        assertEquals("FUNCTION", request.componentType());
        assertEquals("NODE", request.runtimeType());
        assertEquals(1, request.attempt());
        assertEquals("{\"path\": \"/orders\"}", request.input());
        assertEquals("NODE", target.runtimeType());
        awaitCondition(
                () -> runtimeRegistry.find(DEV_RUNTIME_INSTANCE_ID).orElseThrow().inFlight() == 0,
                Duration.ofSeconds(5)
        );
        assertFalse(dispatcher.processNext(Duration.ofMillis(500)));
    }

    @Test
    void progressesToNextFunctionWithPreviousResultAsInput() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000231");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000231");
        UUID secondComponentId = UUID.fromString("30000000-0000-0000-0000-000000000232");
        UUID secondComponentVersionId = UUID.fromString("40000000-0000-0000-0000-000000000232");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 1,
                UUID.fromString("30000000-0000-0000-0000-000000000231"),
                UUID.fromString("40000000-0000-0000-0000-000000000231"));
        insertStep(flowVersionId, "fetch-orders", "FUNCTION", 2, secondComponentId, secondComponentVersionId);
        insertStep(flowVersionId, "build-orders-response", "RESPONSE", 3,
                UUID.fromString("30000000-0000-0000-0000-000000000233"),
                UUID.fromString("40000000-0000-0000-0000-000000000233"));
        invocationRegistry.create(new CreateInvocationRequest(flowId, "flw_orders_list", flowVersionId, "{\"path\":\"/orders\"}"));
        InMemoryRuntimeRegistry singleCapacityRegistry = new InMemoryRuntimeRegistry();
        singleCapacityRegistry.register(new RuntimeInstance(
                "runtime-node-single", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 0, "/tmp/test-single-p1.sock"));
        CapturingRuntimeExecutionGateway gateway = new CapturingRuntimeExecutionGateway();
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, singleCapacityRegistry,
                new ExecutionPlanner(), gateway
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

        // Waits for the RESPONSE step (position 3) too, since it now completes the
        // flow - polling for only 2 rows would spuriously pass the instant the
        // transient FUNCTION-only state is observed, without proving progression
        // actually settled.
        awaitCondition(() -> countStepExecutionsUnchecked() == 3, Duration.ofSeconds(5));
        awaitCondition(() -> singleCapacityRegistry.find("runtime-node-single").orElseThrow().inFlight() == 0,
                Duration.ofSeconds(5));

        var requests = gateway.requests();
        assertEquals(2, requests.size());
        RuntimeExecutionRequest secondRequest = requests.get(1);
        assertEquals(secondComponentVersionId, secondRequest.componentVersionId());
        assertEquals(secondComponentId, secondRequest.componentId());
        assertEquals(1, secondRequest.attempt());

        InvocationStepExecution firstExecution =
                stepExecutionRegistry.findById(requests.get(0).executionId()).orElseThrow();
        assertEquals(firstExecution.result(), secondRequest.input());

        InvocationStepExecution secondExecution = stepExecutionRegistry.findById(secondRequest.executionId()).orElseThrow();
        assertEquals(InvocationStepExecutionStatus.COMPLETED, secondExecution.status());
        assertEquals(1, secondExecution.attempt());
        assertEquals(InvocationStepExecutionStatus.COMPLETED, firstExecution.status());
        assertEquals(0, singleCapacityRegistry.find("runtime-node-single").orElseThrow().inFlight());
    }

    @Test
    void doesNotProgressWhenFirstFunctionFails() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000251");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000251");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 1,
                UUID.fromString("30000000-0000-0000-0000-000000000251"),
                UUID.fromString("40000000-0000-0000-0000-000000000251"));
        insertStep(flowVersionId, "fetch-orders", "FUNCTION", 2,
                UUID.fromString("30000000-0000-0000-0000-000000000252"),
                UUID.fromString("40000000-0000-0000-0000-000000000252"));
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId, "flw_orders_list", flowVersionId, "{\"path\":\"/orders\"}"
        ));
        InMemoryRuntimeRegistry singleCapacityRegistry = new InMemoryRuntimeRegistry();
        singleCapacityRegistry.register(new RuntimeInstance(
                "runtime-node-single", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 0, "/tmp/test-single-p2.sock"));
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, singleCapacityRegistry,
                new ExecutionPlanner(), new EagerCompletingGateway().failWith("FAKE_RUNTIME_ERROR")
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

        awaitCondition(() -> singleCapacityRegistry.find("runtime-node-single").orElseThrow().inFlight() == 0,
                Duration.ofSeconds(5));
        assertEquals(1, countStepExecutions());
        InvocationStepExecution failed = firstStepExecution().orElseThrow();
        assertEquals(InvocationStepExecutionStatus.FAILED, failed.status());
        assertEquals(invocation.invocationId(), failed.invocationId());

        awaitCondition(
                () -> invocationRegistry.findById(invocation.invocationId()).orElseThrow().status() == InvocationStatus.FAILED,
                Duration.ofSeconds(5)
        );
        Invocation failedInvocation = invocationRegistry.findById(invocation.invocationId()).orElseThrow();
        assertEquals(InvocationStatus.FAILED, failedInvocation.status());
        assertNotNull(failedInvocation.completedAt());
        assertEquals(null, failedInvocation.result());
    }

    @Test
    void firstStepReceivesInvocationInputRegardlessOfItsNumericPosition() throws Exception {
        // A valid Flow may use non-contiguous positions like 10/20/30; the
        // Dispatcher must not infer "first step" from position == 1.
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000271");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000271");
        UUID firstComponentId = UUID.fromString("30000000-0000-0000-0000-000000000271");
        UUID firstComponentVersionId = UUID.fromString("40000000-0000-0000-0000-000000000271");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 10, firstComponentId, firstComponentVersionId);
        insertStep(flowVersionId, "fetch-orders", "FUNCTION", 20,
                UUID.fromString("30000000-0000-0000-0000-000000000272"),
                UUID.fromString("40000000-0000-0000-0000-000000000272"));
        insertStep(flowVersionId, "build-orders-response", "RESPONSE", 30,
                UUID.fromString("30000000-0000-0000-0000-000000000273"),
                UUID.fromString("40000000-0000-0000-0000-000000000273"));
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId, "flw_orders_list", flowVersionId, "{\"path\":\"/orders\",\"marker\":\"first-step-input\"}"
        ));
        CapturingRuntimeExecutionGateway gateway = new CapturingRuntimeExecutionGateway();
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                new ExecutionPlanner(), gateway
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

        RuntimeExecutionRequest firstRequest = gateway.capturedRequest();
        assertEquals(firstComponentId, firstRequest.componentId());
        assertEquals(firstComponentVersionId, firstRequest.componentVersionId());
        assertJsonEquals(invocation.inputPayload(), firstRequest.input());
    }

    @Test
    void responseStepDoesNotReserveRuntimeCapacity() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000281");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000281");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 1,
                UUID.fromString("30000000-0000-0000-0000-000000000281"),
                UUID.fromString("40000000-0000-0000-0000-000000000281"));
        insertStep(flowVersionId, "build-orders-response", "RESPONSE", 2,
                UUID.fromString("30000000-0000-0000-0000-000000000282"),
                UUID.fromString("40000000-0000-0000-0000-000000000282"));
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId, "flw_orders_list", flowVersionId, "{\"path\":\"/orders\"}"
        ));
        CountingRuntimeRegistry countingRuntimeRegistry = new CountingRuntimeRegistry(runtimeRegistry);
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, countingRuntimeRegistry
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

        awaitCondition(
                () -> invocationRegistry.findById(invocation.invocationId()).orElseThrow().status() == InvocationStatus.COMPLETED,
                Duration.ofSeconds(5)
        );
        assertEquals(2, countStepExecutions());
        // Exactly one reservation: the FUNCTION step. The RESPONSE step that
        // followed and completed the Invocation never touched the registry.
        assertEquals(1, countingRuntimeRegistry.selectAndReserveCallCount());
        assertEquals(1, countingRuntimeRegistry.releaseCallCount());
    }

    @Test
    void completionEventIsPublishedOnlyAfterDurablePersistence() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000291");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000291");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 1,
                UUID.fromString("30000000-0000-0000-0000-000000000291"),
                UUID.fromString("40000000-0000-0000-0000-000000000291"));
        insertStep(flowVersionId, "build-orders-response", "RESPONSE", 2,
                UUID.fromString("30000000-0000-0000-0000-000000000292"),
                UUID.fromString("40000000-0000-0000-0000-000000000292"));
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId, "flw_orders_list", flowVersionId, "{\"path\":\"/orders\"}"
        ));
        io.nats.client.Subscription terminalSubscription =
                natsConnection.subscribe(InvocationMessagingConfig.INVOCATION_TERMINAL_SUBJECT);
        natsConnection.flush(Duration.ofSeconds(5));
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, runtimeRegistry
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

        io.nats.client.Message terminalMessage = terminalSubscription.nextMessage(Duration.ofSeconds(5));
        assertNotNull(terminalMessage, "Expected an INVOCATION_COMPLETED event to be published");
        var event = OBJECT_MAPPER.readTree(terminalMessage.getData());
        assertEquals("INVOCATION_COMPLETED", event.at("/eventType").asText());
        assertEquals(invocation.invocationId().toString(), event.at("/invocationId").asText());

        // The event only arrives after persistence completed, so the durable
        // read must already show COMPLETED by the time it's observed here.
        Invocation persisted = invocationRegistry.findById(invocation.invocationId()).orElseThrow();
        assertEquals(InvocationStatus.COMPLETED, persisted.status());
    }

    @Test
    void exceptionalCompletionFailsStepInvocationAndReleasesCapacity() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000271");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000271");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 1,
                UUID.fromString("30000000-0000-0000-0000-000000000271"),
                UUID.fromString("40000000-0000-0000-0000-000000000271"));
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId, "flw_orders_list", flowVersionId, "{\"path\":\"/orders\"}"
        ));
        InMemoryRuntimeRegistry singleCapacityRegistry = new InMemoryRuntimeRegistry();
        singleCapacityRegistry.register(new RuntimeInstance(
                "runtime-node-single", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 0, "/tmp/test-single-p3.sock"));
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, singleCapacityRegistry,
                new ExecutionPlanner(), new ExceptionalCompletionGateway()
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

        awaitCondition(() -> singleCapacityRegistry.find("runtime-node-single").orElseThrow().inFlight() == 0,
                Duration.ofSeconds(5));
        assertEquals(1, countStepExecutions());
        InvocationStepExecution failed = firstStepExecution().orElseThrow();
        assertEquals(InvocationStepExecutionStatus.FAILED, failed.status());
        assertTrue(failed.error() != null && failed.error().contains("RUNTIME_EXECUTION_FAILED"));
        assertEquals(InvocationStatus.FAILED,
                invocationRegistry.findById(invocation.invocationId()).orElseThrow().status());
    }

    @Test
    void duplicateFailureAfterExceptionalCompletionDoesNotReleaseCapacityTwice() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000281");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000281");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 1,
                UUID.fromString("30000000-0000-0000-0000-000000000281"),
                UUID.fromString("40000000-0000-0000-0000-000000000281"));
        invocationRegistry.create(new CreateInvocationRequest(
                flowId, "flw_orders_list", flowVersionId, "{\"path\":\"/orders\"}"
        ));
        InMemoryRuntimeRegistry singleCapacityRegistry = new InMemoryRuntimeRegistry();
        singleCapacityRegistry.register(new RuntimeInstance(
                "runtime-node-single", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 0, "/tmp/test-single-p4.sock"));
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, singleCapacityRegistry,
                new ExecutionPlanner(), new ExceptionalCompletionGateway()
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));
        awaitCondition(() -> singleCapacityRegistry.find("runtime-node-single").orElseThrow().inFlight() == 0,
                Duration.ofSeconds(5));

        InvocationStepExecution failed = firstStepExecution().orElseThrow();
        InvocationStepExecutionTransition duplicate = stepExecutionRegistry.markFailed(
                failed.id(),
                RuntimeExecutionResult.failure(failed.id(),
                        new RuntimeExecutionError("RUNTIME_EXECUTION_FAILED", "Simulated IPC failure after acceptance")));

        assertFalse(duplicate.transitioned());
        assertEquals(0, singleCapacityRegistry.find("runtime-node-single").orElseThrow().inFlight());
        assertEquals(1, countStepExecutions());
    }

    @Test
    void unsupportedNextStepTypeFailsTheInvocation() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000291");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000291");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 100,
                UUID.fromString("30000000-0000-0000-0000-000000000291"),
                UUID.fromString("40000000-0000-0000-0000-000000000291"));
        insertStep(flowVersionId, "log-request", "MIDDLEWARE", 101,
                UUID.fromString("30000000-0000-0000-0000-000000000292"),
                UUID.fromString("40000000-0000-0000-0000-000000000292"));
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flowId, "flw_orders_list", flowVersionId, "{\"path\":\"/orders\"}"
        ));
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                new ExecutionPlanner(), new EagerCompletingGateway()
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

        awaitCondition(
                () -> invocationRegistry.findById(invocation.invocationId()).orElseThrow().status()
                        == InvocationStatus.FAILED,
                Duration.ofSeconds(5));
        assertEquals(InvocationStatus.FAILED,
                invocationRegistry.findById(invocation.invocationId()).orElseThrow().status());

        assertEquals(1, countStepExecutions());
        InvocationStepExecution first = firstStepExecution().orElseThrow();
        assertEquals(InvocationStepExecutionStatus.COMPLETED, first.status());
    }

    @Test
    void duplicateStepOneCompletionDoesNotCreateMoreExecutions() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000261");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000261");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(flowVersionId, "validate-orders-request", "FUNCTION", 1,
                UUID.fromString("30000000-0000-0000-0000-000000000261"),
                UUID.fromString("40000000-0000-0000-0000-000000000261"));
        insertStep(flowVersionId, "fetch-orders", "FUNCTION", 2,
                UUID.fromString("30000000-0000-0000-0000-000000000262"),
                UUID.fromString("40000000-0000-0000-0000-000000000262"));
        insertStep(flowVersionId, "build-orders-response", "RESPONSE", 3,
                UUID.fromString("30000000-0000-0000-0000-000000000263"),
                UUID.fromString("40000000-0000-0000-0000-000000000263"));
        invocationRegistry.create(new CreateInvocationRequest(flowId, "flw_orders_list", flowVersionId, "{\"path\":\"/orders\"}"));
        CapturingRuntimeExecutionGateway gateway = new CapturingRuntimeExecutionGateway();
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                new ExecutionPlanner(), gateway
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));
        awaitCondition(() -> countStepExecutionsUnchecked() == 3, Duration.ofSeconds(5));

        InvocationStepExecution firstExecution =
                stepExecutionRegistry.findById(gateway.requests().get(0).executionId()).orElseThrow();
        InvocationStepExecutionTransition duplicate =
                stepExecutionRegistry.markCompleted(
                        firstExecution.id(),
                        RuntimeExecutionResult.success(firstExecution.id(), firstExecution.result())
                );

        assertFalse(duplicate.transitioned());
        assertEquals(3, countStepExecutions());
    }

    @Test
    void releasesReservedCapacityAndDoesNotAckWhenHandoffRejected() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000171");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000171");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(
                flowVersionId,
                "validate-orders-request",
                "FUNCTION",
                1,
                UUID.fromString("30000000-0000-0000-0000-000000000171"),
                UUID.fromString("40000000-0000-0000-0000-000000000171")
        );
        invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_orders_list",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        InMemoryRuntimeRegistry singleCapacityRegistry = new InMemoryRuntimeRegistry();
        singleCapacityRegistry.register(new RuntimeInstance(
                "runtime-node-single", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 0, "/tmp/test-single.sock"));
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, singleCapacityRegistry,
                new ExecutionPlanner(), new RejectingRuntimeExecutionGateway()
        );

        assertFalse(dispatcher.processNext(Duration.ofSeconds(5)));

        assertEquals(0, singleCapacityRegistry.find("runtime-node-single").orElseThrow().inFlight());
        assertEquals(1, countStepExecutions());
    }

    @Test
    void releasesReservedCapacityAndDoesNotAckWhenHandoffThrows() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000181");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000181");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(
                flowVersionId,
                "validate-orders-request",
                "FUNCTION",
                1,
                UUID.fromString("30000000-0000-0000-0000-000000000181"),
                UUID.fromString("40000000-0000-0000-0000-000000000181")
        );
        invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_orders_list",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        InMemoryRuntimeRegistry singleCapacityRegistry = new InMemoryRuntimeRegistry();
        singleCapacityRegistry.register(new RuntimeInstance(
                "runtime-node-single", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 0, "/tmp/test-single.sock"));
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection, invocationRegistry, stepExecutionRegistry, singleCapacityRegistry,
                new ExecutionPlanner(), new ThrowingRuntimeExecutionGateway()
        );

        assertFalse(dispatcher.processNext(Duration.ofSeconds(5)));

        assertEquals(0, singleCapacityRegistry.find("runtime-node-single").orElseThrow().inFlight());
    }

    @Test
    void realIpcHandoffCompletesAndReleasesReservationThenAcks() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000191");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000191");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(
                flowVersionId,
                "validate-orders-request",
                "FUNCTION",
                1,
                UUID.fromString("30000000-0000-0000-0000-000000000191"),
                UUID.fromString("40000000-0000-0000-0000-000000000191")
        );
        invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_orders_list",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        try (FakeIpcWorker worker = FakeIpcWorker.start(); IpcRuntimeExecutionGateway ipcGateway = new IpcRuntimeExecutionGateway(Duration.ofSeconds(2))) {
            InMemoryRuntimeRegistry ipcRuntimeRegistry = new InMemoryRuntimeRegistry();
            ipcRuntimeRegistry.register(new RuntimeInstance(
                    "runtime-node-ipc", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 0, worker.socketPath()));
            InvocationDispatcher dispatcher = new InvocationDispatcher(
                    natsConnection, invocationRegistry, stepExecutionRegistry, ipcRuntimeRegistry,
                    new ExecutionPlanner(), ipcGateway
            );

            assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

            awaitCondition(
                    () -> ipcRuntimeRegistry.find("runtime-node-ipc").orElseThrow().inFlight() == 0,
                    Duration.ofSeconds(5)
            );
            assertEquals(1, worker.receivedExecutionIds().size());
        }
    }

    @Test
    void doesNotReleaseReservedCapacityWhenTerminalPersistenceFails() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000211");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000211");
        insertFlow(flowId, "flw_terminal_persistence_failure", flowVersionId, 1);
        insertStep(
                flowVersionId,
                "validate-orders-request",
                "FUNCTION",
                1,
                UUID.fromString("30000000-0000-0000-0000-000000000211"),
                UUID.fromString("40000000-0000-0000-0000-000000000211")
        );
        invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_terminal_persistence_failure",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        InMemoryRuntimeRegistry singleCapacityRegistry = new InMemoryRuntimeRegistry();
        singleCapacityRegistry.register(new RuntimeInstance(
                "runtime-node-single", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 0, "/tmp/test-single.sock"));
        TerminalFailingInvocationStepExecutionRegistry terminalFailingRegistry =
                new TerminalFailingInvocationStepExecutionRegistry(stepExecutionRegistry);
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection,
                invocationRegistry,
                terminalFailingRegistry,
                singleCapacityRegistry,
                new ExecutionPlanner(),
                new InMemoryRuntimeExecutionGateway()
        );

        assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

        awaitCondition(() -> terminalFailingRegistry.terminalAttemptCount() > 0, Duration.ofSeconds(5));
        assertEquals(1, singleCapacityRegistry.find("runtime-node-single").orElseThrow().inFlight());
    }

    @Test
    void realIpcHandoffFailureReleasesReservationAndDoesNotAck() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000201");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000201");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(
                flowVersionId,
                "validate-orders-request",
                "FUNCTION",
                1,
                UUID.fromString("30000000-0000-0000-0000-000000000201"),
                UUID.fromString("40000000-0000-0000-0000-000000000201")
        );
        invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_orders_list",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        try (IpcRuntimeExecutionGateway ipcGateway = new IpcRuntimeExecutionGateway(Duration.ofMillis(300))) {
            InMemoryRuntimeRegistry ipcRuntimeRegistry = new InMemoryRuntimeRegistry();
            ipcRuntimeRegistry.register(new RuntimeInstance(
                    "runtime-node-ipc-down", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 0, "/tmp/fh-no-worker-here.sock"));
            InvocationDispatcher dispatcher = new InvocationDispatcher(
                    natsConnection, invocationRegistry, stepExecutionRegistry, ipcRuntimeRegistry,
                    new ExecutionPlanner(), ipcGateway
            );

            assertFalse(dispatcher.processNext(Duration.ofSeconds(5)));

            assertEquals(0, ipcRuntimeRegistry.find("runtime-node-ipc-down").orElseThrow().inFlight());
        }
    }

    @Test
    void terminalPersistenceRunsOnDispatcherCompletionExecutorNotIpcReaderThread() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000221");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000221");
        insertFlow(flowId, "flw_orders_list", flowVersionId, 1);
        insertStep(
                flowVersionId,
                "validate-orders-request",
                "FUNCTION",
                1,
                UUID.fromString("30000000-0000-0000-0000-000000000221"),
                UUID.fromString("40000000-0000-0000-0000-000000000221")
        );
        invocationRegistry.create(new CreateInvocationRequest(
                flowId,
                "flw_orders_list",
                flowVersionId,
                "{\"path\":\"/orders\"}"
        ));
        try (FakeIpcWorker worker = FakeIpcWorker.start(); IpcRuntimeExecutionGateway ipcGateway = new IpcRuntimeExecutionGateway(Duration.ofSeconds(2))) {
            InMemoryRuntimeRegistry ipcRuntimeRegistry = new InMemoryRuntimeRegistry();
            ipcRuntimeRegistry.register(new RuntimeInstance(
                    "runtime-node-thread-check", "NODE", RuntimeInstanceStatus.AVAILABLE, 1, 0, worker.socketPath()));
            ThreadCapturingInvocationStepExecutionRegistry threadCapturingRegistry =
                    new ThreadCapturingInvocationStepExecutionRegistry(stepExecutionRegistry);
            InvocationDispatcher dispatcher = new InvocationDispatcher(
                    natsConnection, invocationRegistry, threadCapturingRegistry, ipcRuntimeRegistry,
                    new ExecutionPlanner(), ipcGateway
            );

            assertTrue(dispatcher.processNext(Duration.ofSeconds(5)));

            awaitCondition(() -> threadCapturingRegistry.terminalThreadName() != null, Duration.ofSeconds(5));
            String terminalThreadName = threadCapturingRegistry.terminalThreadName();
            assertTrue(terminalThreadName.startsWith("dispatcher-completion"));
            assertTrue(!terminalThreadName.contains("ipc-runtime-gateway"));
        }
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    private void resetInvocationStream() {
        try {
            natsConnection.jetStreamManagement().deleteStream(InvocationMessagingConfig.STREAM_NAME);
        } catch (Exception ignored) {
            // The stream is created lazily by the publisher/dispatcher, so missing stream is fine here.
        }
    }

    private void insertFlow(UUID flowId, String flowKey, UUID flowVersionId, int version) throws Exception {
        try (var connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into flows (
                        id,
                        app_user_id,
                        gateway_id,
                        active_flow_version_id,
                        active_flow_version_status,
                        flow_key,
                        name,
                        http_method,
                        path
                    )
                    values (
                        '%s',
                        '22222222-2222-2222-2222-222222222222',
                        '44444444-4444-4444-4444-444444444444',
                        '%s',
                        'ADOPTED',
                        '%s',
                        '%s',
                        'POST',
                        '/dispatch'
                    )
                    """.formatted(flowId, flowVersionId, flowKey, flowKey));
            statement.execute("""
                    insert into flow_versions (
                        id,
                        flow_id,
                        version,
                        status,
                        runtime
                    )
                    values ('%s', '%s', %s, 'ADOPTED', 'NODE')
                    """.formatted(flowVersionId, flowId, version));
        }
    }

    private void insertStep(
            UUID flowVersionId,
            String stepKey,
            String componentType,
            int position,
            UUID componentId,
            UUID componentVersionId
    ) throws Exception {
        try (var connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into flow_steps (
                        id,
                        flow_version_id,
                        step_key,
                        component_type,
                        position,
                        component_id,
                        component_version_id
                    )
                    values (
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %s,
                        '%s',
                        '%s'
                    )
                    """.formatted(UUID.randomUUID(), flowVersionId, stepKey, componentType, position, componentId, componentVersionId));
        }
    }

    /**
     * Terminal RESULT/ERROR handling now always completes asynchronously on
     * {@link InvocationDispatcher}'s own completion executor (see the
     * Section-0 fix), never inline on the calling/reader thread - so tests
     * must poll for the eventual state rather than asserting immediately
     * after {@code processNext} returns.
     */
    private void awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadlineMillis) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    private void assertJsonEquals(String expected, String actual) throws Exception {
        assertEquals(OBJECT_MAPPER.readTree(expected), OBJECT_MAPPER.readTree(actual));
    }

    private java.util.Optional<InvocationStepExecution> stepExecutionAtPosition(UUID flowVersionId, int position) throws Exception {
        try (
                var connection = dataSource().getConnection();
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "select id from invocation_step_executions where flow_version_id = '"
                                + flowVersionId + "' and position = " + position)
        ) {
            if (resultSet.next()) {
                return stepExecutionRegistry.findById(java.util.UUID.fromString(resultSet.getString(1)));
            }
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<InvocationStepExecution> firstStepExecution() throws Exception {
        try (
                var connection = dataSource().getConnection();
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "select id from invocation_step_executions order by created_at limit 1")
        ) {
            if (resultSet.next()) {
                return stepExecutionRegistry.findById(java.util.UUID.fromString(resultSet.getString(1)));
            }
            return java.util.Optional.empty();
        }
    }

    private int countStepExecutionsUnchecked() {
        try {
            return countStepExecutions();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private int countStepExecutions() throws Exception {
        try (
                var connection = dataSource().getConnection();
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("select count(*) from invocation_step_executions")
        ) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static final class MissingInvocationRegistry implements InvocationRegistry {

        @Override
        public Invocation createDirectInvocation(DirectInvocationRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Invocation create(CreateInvocationRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Optional<Invocation> findById(UUID invocationId) {
            return Optional.empty();
        }

        @Override
        public InvocationTransition markCompleted(UUID invocationId, String result) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public InvocationTransition markFailed(UUID invocationId, String error) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class RecordingExecutionPlanner extends ExecutionPlanner {
        private DispatchableStep plannedStep;

        @Override
        public DispatchableStep planInitialStep(Invocation invocation, InvocationSnapshot snapshot) {
            plannedStep = super.planInitialStep(invocation, snapshot);
            return plannedStep;
        }

        boolean planned() {
            return plannedStep != null;
        }

        DispatchableStep plannedStep() {
            return plannedStep;
        }
    }

    private static final class FailingInvocationStepExecutionRegistry implements InvocationStepExecutionRegistry {

        @Override
        public InvocationStepExecution createOrGetReadyExecution(DispatchableStep dispatchableStep) {
            throw new IllegalStateException("Simulated step execution persistence failure");
        }

        @Override
        public InvocationStepExecution markRunning(UUID executionId, String runtimeInstanceId) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public InvocationStepExecutionTransition markCompleted(UUID executionId, RuntimeExecutionResult result) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public InvocationStepExecutionTransition markFailed(UUID executionId, RuntimeExecutionResult result) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class TerminalFailingInvocationStepExecutionRegistry implements InvocationStepExecutionRegistry {

        private final InvocationStepExecutionRegistry delegate;
        private final java.util.concurrent.atomic.AtomicInteger terminalAttemptCount = new java.util.concurrent.atomic.AtomicInteger();

        private TerminalFailingInvocationStepExecutionRegistry(InvocationStepExecutionRegistry delegate) {
            this.delegate = delegate;
        }

        int terminalAttemptCount() {
            return terminalAttemptCount.get();
        }

        @Override
        public InvocationStepExecution createOrGetReadyExecution(DispatchableStep dispatchableStep) {
            return delegate.createOrGetReadyExecution(dispatchableStep);
        }

        @Override
        public InvocationStepExecution markRunning(UUID executionId, String runtimeInstanceId) {
            return delegate.markRunning(executionId, runtimeInstanceId);
        }

        @Override
        public InvocationStepExecutionTransition markCompleted(UUID executionId, RuntimeExecutionResult result) {
            terminalAttemptCount.incrementAndGet();
            throw new IllegalStateException("Simulated terminal persistence failure");
        }

        @Override
        public InvocationStepExecutionTransition markFailed(UUID executionId, RuntimeExecutionResult result) {
            terminalAttemptCount.incrementAndGet();
            throw new IllegalStateException("Simulated terminal persistence failure");
        }
    }

    private static final class CountingRuntimeRegistry implements RuntimeRegistry {

        private final RuntimeRegistry delegate;
        private final java.util.concurrent.atomic.AtomicInteger selectAndReserveCallCount = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger releaseCallCount = new java.util.concurrent.atomic.AtomicInteger();

        private CountingRuntimeRegistry(RuntimeRegistry delegate) {
            this.delegate = delegate;
        }

        int selectAndReserveCallCount() {
            return selectAndReserveCallCount.get();
        }

        int releaseCallCount() {
            return releaseCallCount.get();
        }

        @Override
        public void register(RuntimeInstance runtimeInstance) {
            delegate.register(runtimeInstance);
        }

        @Override
        public void unregister(String runtimeInstanceId) {
            delegate.unregister(runtimeInstanceId);
        }

        @Override
        public RuntimeTarget selectAndReserve(RuntimeRequirement requirement) {
            selectAndReserveCallCount.incrementAndGet();
            return delegate.selectAndReserve(requirement);
        }

        @Override
        public void release(String runtimeInstanceId) {
            releaseCallCount.incrementAndGet();
            delegate.release(runtimeInstanceId);
        }

        @Override
        public Optional<RuntimeInstance> find(String runtimeInstanceId) {
            return delegate.find(runtimeInstanceId);
        }
    }

    private static final class ThreadCapturingInvocationStepExecutionRegistry implements InvocationStepExecutionRegistry {

        private final InvocationStepExecutionRegistry delegate;
        private volatile String terminalThreadName;

        private ThreadCapturingInvocationStepExecutionRegistry(InvocationStepExecutionRegistry delegate) {
            this.delegate = delegate;
        }

        String terminalThreadName() {
            return terminalThreadName;
        }

        @Override
        public InvocationStepExecution createOrGetReadyExecution(DispatchableStep dispatchableStep) {
            return delegate.createOrGetReadyExecution(dispatchableStep);
        }

        @Override
        public InvocationStepExecution markRunning(UUID executionId, String runtimeInstanceId) {
            return delegate.markRunning(executionId, runtimeInstanceId);
        }

        @Override
        public InvocationStepExecutionTransition markCompleted(UUID executionId, RuntimeExecutionResult result) {
            terminalThreadName = Thread.currentThread().getName();
            return delegate.markCompleted(executionId, result);
        }

        @Override
        public InvocationStepExecutionTransition markFailed(UUID executionId, RuntimeExecutionResult result) {
            terminalThreadName = Thread.currentThread().getName();
            return delegate.markFailed(executionId, result);
        }
    }

    private static final class CapturingRuntimeExecutionGateway implements RuntimeExecutionGateway {

        private final InMemoryRuntimeExecutionGateway delegate = new InMemoryRuntimeExecutionGateway();
        private final java.util.List<RuntimeExecutionRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        private RuntimeTarget capturedTarget;
        private RuntimeExecutionRequest capturedRequest;

        @Override
        public RuntimeExecutionHandle handoff(RuntimeTarget target, RuntimeExecutionRequest request) {
            capturedTarget = target;
            capturedRequest = request;
            requests.add(request);
            return delegate.handoff(target, request);
        }

        RuntimeTarget capturedTarget() {
            return capturedTarget;
        }

        RuntimeExecutionRequest capturedRequest() {
            return capturedRequest;
        }

        java.util.List<RuntimeExecutionRequest> requests() {
            return requests;
        }
    }

    /**
     * Fake gateway that accepts every handoff and completes the execution
     * immediately with the configured terminal result, letting tests drive
     * deterministic RESULT / ERROR lifecycles without IPC.
     */
    private static final class EagerCompletingGateway implements RuntimeExecutionGateway {

        private volatile String errorCode;
        private final java.util.List<RuntimeExecutionRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

        EagerCompletingGateway failWith(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        @Override
        public RuntimeExecutionHandle handoff(RuntimeTarget target, RuntimeExecutionRequest request) {
            requests.add(request);
            RuntimeExecutionResult terminal = errorCode == null
                    ? RuntimeExecutionResult.success(request.executionId(),
                            "{\"ok\":true,\"executionId\":\"" + request.executionId() + "\"}")
                    : RuntimeExecutionResult.failure(request.executionId(),
                            new RuntimeExecutionError(errorCode, "Simulated runtime failure"));
            return new RuntimeExecutionHandle(
                    RuntimeExecutionAcceptance.accept(request.executionId()),
                    java.util.concurrent.CompletableFuture.completedFuture(terminal)
            );
        }

        java.util.List<RuntimeExecutionRequest> requests() {
            return requests;
        }
    }

    /**
     * Fake gateway that accepts the handoff and then fails the completion
     * future exceptionally - the IPC/worker-died-after-ACCEPTED case.
     */
    private static final class ExceptionalCompletionGateway implements RuntimeExecutionGateway {

        @Override
        public RuntimeExecutionHandle handoff(RuntimeTarget target, RuntimeExecutionRequest request) {
            return new RuntimeExecutionHandle(
                    RuntimeExecutionAcceptance.accept(request.executionId()),
                    java.util.concurrent.CompletableFuture.failedFuture(
                            new IllegalStateException("Simulated IPC failure after acceptance"))
            );
        }
    }

    private static final class RejectingRuntimeExecutionGateway implements RuntimeExecutionGateway {

        @Override
        public RuntimeExecutionHandle handoff(RuntimeTarget target, RuntimeExecutionRequest request) {
            return new RuntimeExecutionHandle(
                    RuntimeExecutionAcceptance.reject(request.executionId(), "simulated handoff rejection"),
                    java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("simulated handoff rejection"))
            );
        }
    }

    private static final class ThrowingRuntimeExecutionGateway implements RuntimeExecutionGateway {

        @Override
        public RuntimeExecutionHandle handoff(RuntimeTarget target, RuntimeExecutionRequest request) {
            throw new IllegalStateException("Simulated runtime gateway failure");
        }
    }
}
