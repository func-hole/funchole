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

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("funchole")
            .withUsername("funchole")
            .withPassword("funchole");

    private JdbcInvocationRegistry registry;

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
        }
        registry = new JdbcInvocationRegistry(dataSource);
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
        assertEquals(flowId, retrieved.flowId());
        assertEquals("flw_checkout", retrieved.flowKey());
        assertEquals(flowVersionId, retrieved.flowVersionId());
        assertEquals(InvocationStatus.PENDING, retrieved.status());
        assertJsonEquals(inputPayload, retrieved.inputPayload());
        assertSnapshotContainsRoot(retrieved.dependencySnapshot(), flowId, "flw_checkout", flowVersionId);
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
}
