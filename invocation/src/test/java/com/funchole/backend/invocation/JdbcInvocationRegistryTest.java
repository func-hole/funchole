package com.funchole.backend.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcInvocationRegistryTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID ORDERS_FLOW_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ORDERS_FLOW_VERSION_ID = UUID.fromString("66666666-6666-6666-6666-666666666661");
    private static final UUID VALIDATE_ORDERS_STEP_ID = UUID.fromString("77777777-7777-7777-7777-777777777761");
    private static final UUID FETCH_ORDERS_STEP_ID = UUID.fromString("77777777-7777-7777-7777-777777777762");
    private static final UUID BUILD_ORDERS_RESPONSE_STEP_ID = UUID.fromString("77777777-7777-7777-7777-777777777763");
    private static final UUID VALIDATE_ORDERS_COMPONENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888861");
    private static final UUID FETCH_ORDERS_COMPONENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888862");
    private static final UUID BUILD_ORDERS_RESPONSE_COMPONENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888863");
    private static final UUID VALIDATE_ORDERS_COMPONENT_VERSION_ID = UUID.fromString("99999999-9999-9999-9999-999999999861");
    private static final UUID FETCH_ORDERS_COMPONENT_VERSION_ID = UUID.fromString("99999999-9999-9999-9999-999999999862");
    private static final UUID BUILD_ORDERS_RESPONSE_COMPONENT_VERSION_ID = UUID.fromString("99999999-9999-9999-9999-999999999863");

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("funchole")
            .withUsername("funchole")
            .withPassword("funchole");

    private JdbcInvocationRegistry registry;
    private CapturingInvocationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()
        ) {
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
                        flow_id UUID not null,
                        flow_key VARCHAR(150) not null,
                        flow_version_id UUID not null,
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
        }
        eventPublisher = new CapturingInvocationEventPublisher();
        registry = new JdbcInvocationRegistry(dataSource, eventPublisher);
    }

    @Test
    void createsPendingInvocationAndRetrievesIt() throws Exception {
        UUID flowId = UUID.fromString("55555555-5555-5555-5555-555555555553");
        UUID flowVersionId = UUID.fromString("66666666-6666-6666-6666-666666666663");
        insertFlow(flowId, "flw_checkout", flowVersionId, 1);
        String inputPayload = """
                {"method":"POST","path":"/checkout","body":"{\\"total\\":100}"}
                """;

        Invocation invocation = registry.create(new CreateInvocationRequest(
                flowId,
                "flw_checkout",
                flowVersionId,
                inputPayload
        ));

        assertNotNull(invocation.invocationId());
        assertEquals(InvocationKind.FLOW, invocation.kind());
        assertEquals(flowId, invocation.flowId());
        assertEquals("flw_checkout", invocation.flowKey());
        assertEquals(flowVersionId, invocation.flowVersionId());
        assertEquals(InvocationStatus.PENDING, invocation.status());
        assertJsonEquals(inputPayload, invocation.inputPayload());
        assertSnapshotContainsRoot(invocation.dependencySnapshot(), flowId, "flw_checkout", flowVersionId);
        assertNotNull(invocation.createdAt());
        assertNotNull(invocation.updatedAt());

        Invocation retrieved = registry.findById(invocation.invocationId()).orElseThrow();

        assertEquals(invocation.invocationId(), retrieved.invocationId());
        assertEquals(InvocationKind.FLOW, retrieved.kind());
        assertEquals(flowId, retrieved.flowId());
        assertEquals("flw_checkout", retrieved.flowKey());
        assertEquals(flowVersionId, retrieved.flowVersionId());
        assertEquals(InvocationStatus.PENDING, retrieved.status());
        assertJsonEquals(inputPayload, retrieved.inputPayload());
        assertSnapshotContainsRoot(retrieved.dependencySnapshot(), flowId, "flw_checkout", flowVersionId);
    }

    @Test
    void createsDirectFunctionVersionInvocationWithDirectFunctionKind() {
        UUID functionId = UUID.randomUUID();
        UUID functionVersionId = UUID.randomUUID();

        Invocation invocation = registry.createDirectInvocation(new DirectInvocationRequest(
                functionId,
                "fn_checkout",
                functionVersionId,
                "NODE",
                "{}"
        ));

        assertEquals(InvocationKind.DIRECT_FUNCTION, invocation.kind());
        assertEquals(functionId, invocation.flowId());
        assertEquals("fn_checkout", invocation.flowKey());
        assertEquals(functionVersionId, invocation.flowVersionId());
        assertEquals(InvocationStatus.PENDING, invocation.status());

        Invocation retrieved = registry.findById(invocation.invocationId()).orElseThrow();
        assertEquals(InvocationKind.DIRECT_FUNCTION, retrieved.kind());
        assertEquals(functionVersionId, retrieved.flowVersionId());
    }

    @Test
    void generatesUniqueInvocationIds() {
        UUID flowId = UUID.randomUUID();
        UUID flowVersionId = UUID.randomUUID();
        insertFlow(flowId, "flw_checkout", flowVersionId, 1);
        CreateInvocationRequest request = new CreateInvocationRequest(
                flowId,
                "flw_checkout",
                flowVersionId,
                "{}"
        );

        Invocation first = registry.create(request);
        Invocation second = registry.create(request);

        assertNotEquals(first.invocationId(), second.invocationId());
        assertTrue(registry.findById(first.invocationId()).isPresent());
        assertTrue(registry.findById(second.invocationId()).isPresent());
    }

    @Test
    void publishesInvocationReadyAfterInvocationIsPersisted() {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000051");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000051");
        insertFlow(flowId, "flw_publish", flowVersionId, 1);

        Invocation invocation = registry.create(new CreateInvocationRequest(flowId, "flw_publish", flowVersionId, "{}"));

        assertEquals(1, eventPublisher.published.size());
        assertEquals(invocation.invocationId(), eventPublisher.published.getFirst().invocationId());
        assertTrue(registry.findById(invocation.invocationId()).isPresent());
    }

    @Test
    void doesNotPublishInvocationReadyWhenPersistenceFails() {
        assertThrows(
                DependencyGraphResolutionException.class,
                () -> registry.create(new CreateInvocationRequest(
                        UUID.randomUUID(),
                        "flw_missing",
                        UUID.randomUUID(),
                        "{}"
                ))
        );

        assertTrue(eventPublisher.published.isEmpty());
    }

    @Test
    void snapshotsSimpleFlowComponentVersions() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID functionA = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID functionAVersion = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID functionB = UUID.fromString("30000000-0000-0000-0000-000000000002");
        UUID functionBVersion = UUID.fromString("40000000-0000-0000-0000-000000000003");

        insertFlow(flowId, "flw_simple", flowVersionId, 1);
        insertStep(flowVersionId, "validate-cart", "FUNCTION", 1, functionA, functionAVersion);
        insertStep(flowVersionId, "calculate-price", "FUNCTION", 2, functionB, functionBVersion);

        Invocation invocation = registry.create(new CreateInvocationRequest(flowId, "flw_simple", flowVersionId, "{}"));
        JsonNode snapshot = OBJECT_MAPPER.readTree(invocation.dependencySnapshot());

        assertEquals(flowId.toString(), snapshot.at("/rootFlowId").asText());
        assertEquals(flowVersionId.toString(), snapshot.at("/rootFlowVersionId").asText());
        assertEquals(functionAVersion.toString(), snapshot.at("/flows/0/steps/0/componentVersionId").asText());
        assertEquals(functionBVersion.toString(), snapshot.at("/flows/0/steps/1/componentVersionId").asText());
    }

    @Test
    void ordersFlowVersionResolvesFakeExecutableStepsInOrder() throws Exception {
        insertOrdersFlowWithFakeSteps();

        Invocation invocation = registry.create(new CreateInvocationRequest(
                ORDERS_FLOW_ID,
                "flw_orders_list",
                ORDERS_FLOW_VERSION_ID,
                "{\"method\":\"GET\",\"path\":\"/orders\"}"
        ));
        JsonNode steps = OBJECT_MAPPER.readTree(invocation.dependencySnapshot()).at("/flows/0/steps");

        assertEquals(3, steps.size());
        assertEquals(VALIDATE_ORDERS_STEP_ID.toString(), steps.get(0).at("/stepId").asText());
        assertEquals("FUNCTION", steps.get(0).at("/componentType").asText());
        assertEquals(1, steps.get(0).at("/position").asInt());
        assertEquals("Validate Orders Request", OBJECT_MAPPER.readTree(steps.get(0).at("/metadata").asText()).at("/name").asText());
        assertEquals(VALIDATE_ORDERS_COMPONENT_ID.toString(), steps.get(0).at("/componentId").asText());
        assertEquals(VALIDATE_ORDERS_COMPONENT_VERSION_ID.toString(), steps.get(0).at("/componentVersionId").asText());

        assertEquals(FETCH_ORDERS_STEP_ID.toString(), steps.get(1).at("/stepId").asText());
        assertEquals("FUNCTION", steps.get(1).at("/componentType").asText());
        assertEquals(2, steps.get(1).at("/position").asInt());
        assertEquals("Fetch Orders", OBJECT_MAPPER.readTree(steps.get(1).at("/metadata").asText()).at("/name").asText());
        assertEquals(FETCH_ORDERS_COMPONENT_ID.toString(), steps.get(1).at("/componentId").asText());
        assertEquals(FETCH_ORDERS_COMPONENT_VERSION_ID.toString(), steps.get(1).at("/componentVersionId").asText());

        assertEquals(BUILD_ORDERS_RESPONSE_STEP_ID.toString(), steps.get(2).at("/stepId").asText());
        assertEquals("FUNCTION", steps.get(2).at("/componentType").asText());
        assertEquals(3, steps.get(2).at("/position").asInt());
        assertEquals("Build Orders Response", OBJECT_MAPPER.readTree(steps.get(2).at("/metadata").asText()).at("/name").asText());
        assertEquals(BUILD_ORDERS_RESPONSE_COMPONENT_ID.toString(), steps.get(2).at("/componentId").asText());
        assertEquals(BUILD_ORDERS_RESPONSE_COMPONENT_VERSION_ID.toString(), steps.get(2).at("/componentVersionId").asText());
    }

    @Test
    void ordersInvocationSnapshotRemainsImmutableAfterSourceStepsChange() throws Exception {
        insertOrdersFlowWithFakeSteps();

        Invocation invocation = registry.create(new CreateInvocationRequest(
                ORDERS_FLOW_ID,
                "flw_orders_list",
                ORDERS_FLOW_VERSION_ID,
                "{\"method\":\"GET\",\"path\":\"/orders\"}"
        ));
        insertStep(
                ORDERS_FLOW_VERSION_ID,
                "new-source-step-after-invocation",
                "FUNCTION",
                4,
                UUID.fromString("88888888-8888-8888-8888-888888888864"),
                UUID.fromString("99999999-9999-9999-9999-999999999864")
        );

        Invocation retrieved = registry.findById(invocation.invocationId()).orElseThrow();
        JsonNode steps = OBJECT_MAPPER.readTree(retrieved.dependencySnapshot()).at("/flows/0/steps");

        assertEquals(3, steps.size());
        assertFalse(retrieved.dependencySnapshot().contains("new-source-step-after-invocation"));
    }

    @Test
    void existingSnapshotKeepsOriginalVersionsAfterNewerVersionsAreAdded() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000011");
        UUID functionId = UUID.fromString("30000000-0000-0000-0000-000000000011");
        UUID originalFunctionVersion = UUID.fromString("40000000-0000-0000-0000-000000000012");
        UUID newerFunctionVersion = UUID.fromString("40000000-0000-0000-0000-000000000013");

        insertFlow(flowId, "flw_stable", flowVersionId, 1);
        insertStep(flowVersionId, "function-a", "FUNCTION", 1, functionId, originalFunctionVersion);

        Invocation invocation = registry.create(new CreateInvocationRequest(flowId, "flw_stable", flowVersionId, "{}"));
        insertStep(flowVersionId, "function-a-new-context", "FUNCTION", 2, functionId, newerFunctionVersion);
        Invocation retrieved = registry.findById(invocation.invocationId()).orElseThrow();
        JsonNode snapshot = OBJECT_MAPPER.readTree(retrieved.dependencySnapshot());

        assertEquals(originalFunctionVersion.toString(), snapshot.at("/flows/0/steps/0/componentVersionId").asText());
        assertFalse(retrieved.dependencySnapshot().contains(newerFunctionVersion.toString()));
    }

    @Test
    void recursivelySnapshotsSubFlowDependencies() throws Exception {
        UUID rootFlowId = UUID.fromString("10000000-0000-0000-0000-000000000021");
        UUID rootVersionId = UUID.fromString("20000000-0000-0000-0000-000000000023");
        UUID subFlowId = UUID.fromString("10000000-0000-0000-0000-000000000022");
        UUID subVersionId = UUID.fromString("20000000-0000-0000-0000-000000000022");
        UUID functionId = UUID.fromString("30000000-0000-0000-0000-000000000024");
        UUID functionVersionId = UUID.fromString("40000000-0000-0000-0000-000000000024");

        insertFlow(rootFlowId, "flw_root", rootVersionId, 3);
        insertFlow(subFlowId, "flw_payment", subVersionId, 2);
        insertStep(rootVersionId, "payment-flow", "SUB_FLOW", 1, subFlowId, subVersionId);
        insertStep(subVersionId, "charge-card", "FUNCTION", 1, functionId, functionVersionId);

        Invocation invocation = registry.create(new CreateInvocationRequest(rootFlowId, "flw_root", rootVersionId, "{}"));
        JsonNode snapshot = OBJECT_MAPPER.readTree(invocation.dependencySnapshot());

        assertEquals(2, snapshot.get("flows").size());
        assertEquals(rootVersionId.toString(), snapshot.at("/flows/0/flowVersionId").asText());
        assertEquals(subVersionId.toString(), snapshot.at("/flows/1/flowVersionId").asText());
        assertEquals(functionVersionId.toString(), snapshot.at("/flows/1/steps/0/componentVersionId").asText());
    }

    @Test
    void failsWhenSubFlowVersionCannotBeResolvedAndDoesNotPersistInvocation() throws Exception {
        UUID rootFlowId = UUID.fromString("10000000-0000-0000-0000-000000000031");
        UUID rootVersionId = UUID.fromString("20000000-0000-0000-0000-000000000031");
        UUID missingSubFlowId = UUID.fromString("10000000-0000-0000-0000-000000000032");
        UUID missingSubVersionId = UUID.fromString("20000000-0000-0000-0000-000000000032");

        insertFlow(rootFlowId, "flw_invalid", rootVersionId, 1);
        insertStep(rootVersionId, "missing-sub-flow", "SUB_FLOW", 1, missingSubFlowId, missingSubVersionId);

        assertThrows(
                DependencyGraphResolutionException.class,
                () -> registry.create(new CreateInvocationRequest(rootFlowId, "flw_invalid", rootVersionId, "{}"))
        );
        assertEquals(0, countInvocations());
    }

    @Test
    void failsSafelyOnCircularSubFlowDependency() throws Exception {
        UUID flowA = UUID.fromString("10000000-0000-0000-0000-000000000041");
        UUID flowAVersion = UUID.fromString("20000000-0000-0000-0000-000000000041");
        UUID flowB = UUID.fromString("10000000-0000-0000-0000-000000000042");
        UUID flowBVersion = UUID.fromString("20000000-0000-0000-0000-000000000042");

        insertFlow(flowA, "flw_a", flowAVersion, 1);
        insertFlow(flowB, "flw_b", flowBVersion, 1);
        insertStep(flowAVersion, "to-b", "SUB_FLOW", 1, flowB, flowBVersion);
        insertStep(flowBVersion, "to-a", "SUB_FLOW", 1, flowA, flowAVersion);

        assertThrows(
                DependencyGraphResolutionException.class,
                () -> registry.create(new CreateInvocationRequest(flowA, "flw_a", flowAVersion, "{}"))
        );
        assertEquals(0, countInvocations());
    }

    @Test
    void marksInvocationCompletedAndPublishesExactlyOnce() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000051");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000051");
        insertFlow(flowId, "flw_terminal_completed", flowVersionId, 1);
        Invocation invocation = registry.create(new CreateInvocationRequest(flowId, "flw_terminal_completed", flowVersionId, "{}"));

        InvocationTransition first = registry.markCompleted(invocation.invocationId(), "{\"status\":200,\"body\":{\"ok\":true}}");
        InvocationTransition second = registry.markCompleted(invocation.invocationId(), "{\"status\":200,\"body\":{\"ok\":true}}");

        assertTrue(first.transitioned());
        assertFalse(second.transitioned());
        assertEquals(InvocationStatus.COMPLETED, first.invocation().status());
        assertJsonEquals("{\"status\":200,\"body\":{\"ok\":true}}", first.invocation().result());
        assertNotNull(first.invocation().completedAt());
        assertEquals(1, eventPublisher.publishedCompleted.size());
        assertEquals(invocation.invocationId(), eventPublisher.publishedCompleted.getFirst().invocationId());

        Invocation retrieved = registry.findById(invocation.invocationId()).orElseThrow();
        assertEquals(InvocationStatus.COMPLETED, retrieved.status());
    }

    @Test
    void marksInvocationFailedAndPublishesExactlyOnce() throws Exception {
        UUID flowId = UUID.fromString("10000000-0000-0000-0000-000000000052");
        UUID flowVersionId = UUID.fromString("20000000-0000-0000-0000-000000000052");
        insertFlow(flowId, "flw_terminal_failed", flowVersionId, 1);
        Invocation invocation = registry.create(new CreateInvocationRequest(flowId, "flw_terminal_failed", flowVersionId, "{}"));

        InvocationTransition first = registry.markFailed(invocation.invocationId(), "{\"code\":\"ARTIFACT_EXECUTION_ERROR\",\"message\":\"boom\"}");
        InvocationTransition second = registry.markFailed(invocation.invocationId(), "{\"code\":\"ARTIFACT_EXECUTION_ERROR\",\"message\":\"boom\"}");

        assertTrue(first.transitioned());
        assertFalse(second.transitioned());
        assertEquals(InvocationStatus.FAILED, first.invocation().status());
        assertEquals(1, eventPublisher.publishedFailed.size());
        assertEquals(invocation.invocationId(), eventPublisher.publishedFailed.getFirst().invocationId());
        assertTrue(eventPublisher.publishedCompleted.isEmpty());
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    private void assertJsonEquals(String expected, String actual) throws Exception {
        JsonNode expectedJson = OBJECT_MAPPER.readTree(expected);
        JsonNode actualJson = OBJECT_MAPPER.readTree(actual);
        assertEquals(expectedJson, actualJson);
    }

    private void assertSnapshotContainsRoot(String snapshot, UUID flowId, String flowKey, UUID flowVersionId) throws Exception {
        JsonNode snapshotJson = OBJECT_MAPPER.readTree(snapshot);
        assertEquals(flowId.toString(), snapshotJson.at("/rootFlowId").asText());
        assertEquals(flowKey, snapshotJson.at("/rootFlowKey").asText());
        assertEquals(flowVersionId.toString(), snapshotJson.at("/rootFlowVersionId").asText());
    }

    private void insertFlow(UUID flowId, String flowKey, UUID flowVersionId, int version) {
        try (
                Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()
        ) {
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
                        '/checkout'
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
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to insert flow test data", exception);
        }
    }

    private void insertStep(
            UUID flowVersionId,
            String stepKey,
            String componentType,
            int position,
            UUID componentId,
            UUID componentVersionId
    ) {
        try (
                Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()
        ) {
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
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to insert flow step test data", exception);
        }
    }

    private void insertStep(
            UUID stepId,
            UUID flowVersionId,
            String stepKey,
            String componentType,
            int position,
            UUID componentId,
            UUID componentVersionId,
            String metadata
    ) {
        try (
                Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute("""
                    insert into flow_steps (
                        id,
                        flow_version_id,
                        step_key,
                        component_type,
                        position,
                        component_id,
                        component_version_id,
                        metadata
                    )
                    values (
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %s,
                        '%s',
                        '%s',
                        '%s'::jsonb
                    )
                    """.formatted(stepId, flowVersionId, stepKey, componentType, position, componentId, componentVersionId, metadata));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to insert flow step test data", exception);
        }
    }

    private void insertOrdersFlowWithFakeSteps() {
        insertFlow(ORDERS_FLOW_ID, "flw_orders_list", ORDERS_FLOW_VERSION_ID, 1);
        insertStep(
                VALIDATE_ORDERS_STEP_ID,
                ORDERS_FLOW_VERSION_ID,
                "validate-orders-request",
                "FUNCTION",
                1,
                VALIDATE_ORDERS_COMPONENT_ID,
                VALIDATE_ORDERS_COMPONENT_VERSION_ID,
                "{\"name\":\"Validate Orders Request\"}"
        );
        insertStep(
                FETCH_ORDERS_STEP_ID,
                ORDERS_FLOW_VERSION_ID,
                "fetch-orders",
                "FUNCTION",
                2,
                FETCH_ORDERS_COMPONENT_ID,
                FETCH_ORDERS_COMPONENT_VERSION_ID,
                "{\"name\":\"Fetch Orders\"}"
        );
        insertStep(
                BUILD_ORDERS_RESPONSE_STEP_ID,
                ORDERS_FLOW_VERSION_ID,
                "build-orders-response",
                "FUNCTION",
                3,
                BUILD_ORDERS_RESPONSE_COMPONENT_ID,
                BUILD_ORDERS_RESPONSE_COMPONENT_VERSION_ID,
                "{\"name\":\"Build Orders Response\"}"
        );
    }

    private int countInvocations() throws Exception {
        try (
                Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("select count(*) from invocations")
        ) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static final class CapturingInvocationEventPublisher implements InvocationEventPublisher {
        private final List<Invocation> published = new ArrayList<>();
        private final List<Invocation> publishedCompleted = new ArrayList<>();
        private final List<Invocation> publishedFailed = new ArrayList<>();

        @Override
        public void publishInvocationReady(Invocation invocation) {
            published.add(invocation);
        }

        @Override
        public void publishInvocationCompleted(Invocation invocation) {
            publishedCompleted.add(invocation);
        }

        @Override
        public void publishInvocationFailed(Invocation invocation) {
            publishedFailed.add(invocation);
        }
    }
}
