package com.funchole.backend.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.invocationcontract.DirectInvocationRequest;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The current model has no RUNNING InvocationStatus (only PENDING, COMPLETED,
 * FAILED - see {@link InvocationStatus}) and no separate "started" timestamp
 * (only createdAt/updatedAt/completedAt - see {@link Invocation}), so those
 * two aspects of the task's test list are not applicable: there is nothing
 * persisted to inspect that a RUNNING-specific or start-timestamp-specific
 * test could exercise without inventing data.
 */
@Testcontainers
class InvocationInspectionServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
            "com.funchole.backend.gateway",
            "com.funchole.backend.dispatcher",
            "com.funchole.backend.runtime",
            "jakarta.servlet",
            "org.springframework.web",
            "org.springframework.http"
    );

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("funchole")
            .withUsername("funchole")
            .withPassword("funchole");

    private JdbcInvocationRegistry registry;
    private InvocationInspectionService inspectionService;

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
                        flow_key VARCHAR(150) not null
                    )
                    """);
            statement.execute("""
                    create table flow_versions (
                        id UUID primary key,
                        flow_id UUID not null,
                        version INTEGER not null,
                        status VARCHAR(100) not null default 'DRAFT',
                        runtime VARCHAR(100) not null default 'NODE',
                        metadata JSONB
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
                        metadata JSONB
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
        }
        registry = new JdbcInvocationRegistry(dataSource);
        inspectionService = new InvocationInspectionService(registry);
    }

    @Test
    void pendingInvocationCanBeInspected() {
        UUID flowId = UUID.randomUUID();
        UUID flowVersionId = UUID.randomUUID();
        insertFlow(flowId, "flw_checkout", flowVersionId);
        Invocation invocation = registry.create(new CreateInvocationRequest(flowId, "flw_checkout", flowVersionId, "{}"));

        InvocationInspection inspection = inspectionService.inspect(invocation.invocationId());

        assertEquals(invocation.invocationId(), inspection.invocationId());
        assertEquals(InvocationStatus.PENDING, inspection.status());
        assertNull(inspection.result());
        assertNull(inspection.error());
        assertNull(inspection.completedAt());
    }

    @Test
    void completedInvocationExposesDurableResult() throws Exception {
        UUID flowId = UUID.randomUUID();
        UUID flowVersionId = UUID.randomUUID();
        insertFlow(flowId, "flw_checkout", flowVersionId);
        Invocation invocation = registry.create(new CreateInvocationRequest(flowId, "flw_checkout", flowVersionId, "{}"));
        registry.markCompleted(invocation.invocationId(), "{\"status\":200,\"body\":{\"ok\":true}}");

        InvocationInspection inspection = inspectionService.inspect(invocation.invocationId());

        assertEquals(InvocationStatus.COMPLETED, inspection.status());
        assertJsonEquals("{\"status\":200,\"body\":{\"ok\":true}}", inspection.result());
        assertNull(inspection.error());
    }

    @Test
    void failedInvocationExposesDurableError() {
        UUID flowId = UUID.randomUUID();
        UUID flowVersionId = UUID.randomUUID();
        insertFlow(flowId, "flw_checkout", flowVersionId);
        Invocation invocation = registry.create(new CreateInvocationRequest(flowId, "flw_checkout", flowVersionId, "{}"));
        registry.markFailed(invocation.invocationId(), "{\"code\":\"ARTIFACT_EXECUTION_ERROR\",\"message\":\"boom\"}");

        InvocationInspection inspection = inspectionService.inspect(invocation.invocationId());

        assertEquals(InvocationStatus.FAILED, inspection.status());
        assertNull(inspection.result());
        assertTrue(inspection.error().contains("ARTIFACT_EXECUTION_ERROR"));
    }

    @Test
    void directInvocationExposesExactPinnedFunctionVersionId() {
        UUID functionId = UUID.randomUUID();
        UUID functionVersionId = UUID.randomUUID();
        Invocation invocation = registry.createDirectInvocation(new DirectInvocationRequest(
                functionId, "fn_checkout", functionVersionId, "NODE", "{}"));

        InvocationInspection inspection = inspectionService.inspect(invocation.invocationId());

        // The exact pinned id is echoed back unchanged - nothing about this
        // module can resolve an active/latest FunctionVersion (no such
        // repository is even reachable from here), so this also demonstrates
        // requirement 4: no active/latest resolution ever happens on this path.
        assertEquals(functionVersionId, inspection.functionVersionId());
        assertNull(inspection.flowId());
        assertNull(inspection.flowKey());
        assertNull(inspection.flowVersionId());
    }

    @Test
    void normalFlowInvocationRemainsInspectable() {
        UUID flowId = UUID.randomUUID();
        UUID flowVersionId = UUID.randomUUID();
        insertFlow(flowId, "flw_checkout", flowVersionId);
        Invocation invocation = registry.create(new CreateInvocationRequest(flowId, "flw_checkout", flowVersionId, "{}"));

        InvocationInspection inspection = inspectionService.inspect(invocation.invocationId());

        assertEquals(flowId, inspection.flowId());
        assertEquals("flw_checkout", inspection.flowKey());
        assertEquals(flowVersionId, inspection.flowVersionId());
        assertNull(inspection.functionVersionId());
    }

    @Test
    void inspectionUsesPersistedKindWithoutParsingSnapshotStructure() {
        // A DIRECT_FUNCTION-kind row whose dependencySnapshot does not match
        // the old "one flow, one step named invoke-function" shape at all -
        // proves classification comes from the persisted kind column alone.
        UUID invocationId = UUID.randomUUID();
        UUID functionVersionId = UUID.randomUUID();
        insertRawInvocation(invocationId, InvocationKind.DIRECT_FUNCTION.name(), UUID.randomUUID(), "fn_x",
                functionVersionId, "{\"totally\":\"unrelated\",\"shape\":true}");

        InvocationInspection inspection = inspectionService.inspect(invocationId);

        assertEquals(functionVersionId, inspection.functionVersionId());
        assertNull(inspection.flowId());
    }

    @Test
    void malformedDependencySnapshotDoesNotChangeInvocationClassification() {
        UUID invocationId = UUID.randomUUID();
        UUID flowId = UUID.randomUUID();
        UUID flowVersionId = UUID.randomUUID();
        insertRawInvocation(invocationId, InvocationKind.FLOW.name(), flowId, "flw_broken", flowVersionId,
                "{\"nothing\":\"like a real snapshot\"}");

        InvocationInspection inspection = inspectionService.inspect(invocationId);

        assertEquals(flowId, inspection.flowId());
        assertEquals("flw_broken", inspection.flowKey());
        assertNull(inspection.functionVersionId());
    }

    @Test
    void singleStepFlowCannotBeMisclassifiedAsDirectFunction() {
        // Deliberately mimics the OLD (removed) heuristic's shape - one flow,
        // one step, keyed exactly like a direct invocation's step - through
        // a genuine Flow invocation, to prove kind persistence (not snapshot
        // shape) now governs classification.
        UUID flowId = UUID.randomUUID();
        UUID flowVersionId = UUID.randomUUID();
        insertFlow(flowId, "flw_single_step", flowVersionId);
        // "invoke-function" mirrors the literal step key JdbcInvocationRegistry
        // writes internally for a direct invocation's snapshot - now a private
        // implementation detail, not part of invocation-contract.
        insertStep(flowVersionId, "invoke-function", "FUNCTION", 1,
                UUID.randomUUID(), UUID.randomUUID());
        Invocation invocation = registry.create(new CreateInvocationRequest(flowId, "flw_single_step", flowVersionId, "{}"));

        InvocationInspection inspection = inspectionService.inspect(invocation.invocationId());

        assertEquals(InvocationKind.FLOW, invocation.kind());
        assertEquals(flowId, inspection.flowId());
        assertEquals(flowVersionId, inspection.flowVersionId());
        assertNull(inspection.functionVersionId());
    }

    @Test
    void invalidPersistedInvocationKindFailsClearly() {
        UUID invocationId = UUID.randomUUID();
        insertRawInvocation(invocationId, "BOGUS", UUID.randomUUID(), "flw_x", UUID.randomUUID(), "{}");

        assertThrows(IllegalStateException.class, () -> inspectionService.inspect(invocationId));
    }

    @Test
    void missingInvocationIsRejectedClearly() {
        UUID missingId = UUID.randomUUID();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> inspectionService.inspect(missingId)
        );
        assertTrue(exception.getMessage().contains(missingId.toString()));
    }

    @Test
    void inspectionServiceHasNoGatewayDispatcherOrRuntimeProcessLocalDependency() {
        for (Field field : InvocationInspectionService.class.getDeclaredFields()) {
            assertPackageIsAllowed(field.getType());
        }
        for (Constructor<?> constructor : InvocationInspectionService.class.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                assertPackageIsAllowed(parameterType);
            }
        }
        for (Method method : InvocationInspectionService.class.getDeclaredMethods()) {
            assertPackageIsAllowed(method.getReturnType());
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertPackageIsAllowed(parameterType);
            }
        }
    }

    private void assertPackageIsAllowed(Class<?> type) {
        String packageName = type.getPackageName();
        for (String forbidden : FORBIDDEN_PACKAGE_PREFIXES) {
            if (packageName.startsWith(forbidden)) {
                throw new AssertionError("type " + type.getName() + " must not belong to package " + forbidden);
            }
        }
    }

    private void assertJsonEquals(String expected, String actual) throws Exception {
        JsonNode expectedJson = OBJECT_MAPPER.readTree(expected);
        JsonNode actualJson = OBJECT_MAPPER.readTree(actual);
        assertEquals(expectedJson, actualJson);
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    private void insertFlow(UUID flowId, String flowKey, UUID flowVersionId) {
        try (
                Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute("insert into flows (id, flow_key) values ('%s', '%s')".formatted(flowId, flowKey));
            statement.execute("""
                    insert into flow_versions (id, flow_id, version, status, runtime)
                    values ('%s', '%s', 1, 'ADOPTED', 'NODE')
                    """.formatted(flowVersionId, flowId));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to insert flow test data", exception);
        }
    }

    private void insertStep(
            UUID flowVersionId, String stepKey, String componentType, int position, UUID componentId, UUID componentVersionId
    ) {
        try (
                Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute("""
                    insert into flow_steps (id, flow_version_id, step_key, component_type, position, component_id, component_version_id)
                    values ('%s', '%s', '%s', '%s', %s, '%s', '%s')
                    """.formatted(UUID.randomUUID(), flowVersionId, stepKey, componentType, position, componentId, componentVersionId));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to insert flow step test data", exception);
        }
    }

    /**
     * Inserts an {@code invocations} row directly via SQL, bypassing
     * {@link JdbcInvocationRegistry} entirely - used to construct rows the
     * registry's own create paths could never produce (an arbitrary/invalid
     * {@code kind}, or a dependencySnapshot unrelated to the persisted kind),
     * so classification behavior can be tested against exactly what is
     * durably stored rather than what a normal creation path would write.
     */
    private void insertRawInvocation(
            UUID invocationId, String kindLiteral, UUID flowId, String flowKey, UUID flowVersionId, String dependencySnapshotJson
    ) {
        boolean direct = InvocationKind.DIRECT_FUNCTION.name().equals(kindLiteral);
        String flowColumns = direct ? "null, null, null" : "'%s', '%s', '%s'".formatted(flowId, flowKey, flowVersionId);
        String functionColumns = direct
                ? "'%s', '%s', '%s'".formatted(flowId, flowKey, flowVersionId)
                : "null, null, null";
        try (
                Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute("""
                    insert into invocations
                        (id, kind, flow_id, flow_key, flow_version_id, function_id, function_key, function_version_id,
                         status, dependency_snapshot)
                    values ('%s', '%s', %s, %s, 'PENDING', '%s'::jsonb)
                    """.formatted(invocationId, kindLiteral, flowColumns, functionColumns, dependencySnapshotJson));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to insert raw invocation test data", exception);
        }
    }
}
