package com.funchole.backend.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.funchole.backend.invocation.CreateInvocationRequest;
import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationMessagingConfig;
import com.funchole.backend.invocation.InvocationRegistry;
import com.funchole.backend.invocation.InvocationSnapshot;
import com.funchole.backend.invocation.InvocationStatus;
import com.funchole.backend.invocation.JdbcInvocationRegistry;
import com.funchole.backend.invocation.NatsJetStreamInvocationEventPublisher;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
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

    private Connection natsConnection;
    private JdbcInvocationRegistry invocationRegistry;
    private JdbcInvocationStepExecutionRegistry stepExecutionRegistry;

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
                        flow_id UUID not null,
                        flow_key VARCHAR(150) not null,
                        flow_version_id UUID not null,
                        status VARCHAR(100) not null,
                        input_payload JSONB,
                        dependency_snapshot JSONB,
                        created_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP
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
                        status VARCHAR(100) not null,
                        attempt INTEGER not null default 1,
                        created_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
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
        InvocationDispatcher dispatcher = new InvocationDispatcher(natsConnection, invocationRegistry, stepExecutionRegistry);

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
        InvocationDispatcher dispatcher = new InvocationDispatcher(natsConnection, invocationRegistry, stepExecutionRegistry);

        assertFalse(dispatcher.processNext(Duration.ofSeconds(5)));
        assertEquals(invocation.invocationId(), invocationRegistry.findById(invocation.invocationId()).orElseThrow().invocationId());
    }

    @Test
    void doesNotAckAsSuccessWhenInvocationCannotBeLoaded() throws Exception {
        InvocationDispatcher dispatcher = new InvocationDispatcher(natsConnection, new MissingInvocationRegistry(), stepExecutionRegistry);
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
                "FUNCTION",
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
        InvocationDispatcher dispatcher = new InvocationDispatcher(natsConnection, invocationRegistry, stepExecutionRegistry, planner);

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
        assertFalse(dispatcher.processNext(Duration.ofMillis(500)));

        InvocationStepExecution execution = stepExecutionRegistry.createOrGetReadyExecution(step);
        assertEquals(InvocationStepExecutionStatus.READY, execution.status());
        assertEquals(1, execution.attempt());
        assertEquals(invocation.invocationId(), execution.invocationId());
        assertEquals(flowId, execution.flowId());
        assertEquals(flowVersionId, execution.flowVersionId());
        assertEquals(step.stepId(), execution.stepId());
        assertEquals(firstComponentId, execution.componentId());
        assertEquals(firstComponentVersionId, execution.componentVersionId());
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
                new FailingInvocationStepExecutionRegistry()
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
        InvocationDispatcher dispatcher = new InvocationDispatcher(natsConnection, invocationRegistry, stepExecutionRegistry);

        assertFalse(dispatcher.processNext(Duration.ofSeconds(5)));
        assertEquals(InvocationStatus.PENDING, invocationRegistry.findById(invocation.invocationId()).orElseThrow().status());
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
        public Invocation create(CreateInvocationRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Optional<Invocation> findById(UUID invocationId) {
            return Optional.empty();
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
    }
}
