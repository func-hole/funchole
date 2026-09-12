package com.funchole.backend.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the V16 backfill migration
 * ({@code controlplane/src/main/resources/db/migration/V16__backfill_direct_function_invocation_identity.sql})
 * against a hand-seeded legacy row: a DIRECT_FUNCTION invocation still
 * holding its function identity in the old flow_* columns, exactly as every
 * such row looked immediately after V15 added the new function_* columns
 * without migrating existing data. The test schema below deliberately keeps
 * flow_id/flow_key/flow_version_id NOT NULL (the real, unrelaxed constraint
 * inherited from V4) so the migration's own constraint relaxation is what
 * is actually exercised - without it, setting them to null would fail with
 * a real constraint violation instead of succeeding silently.
 * {@link #V16_BACKFILL_SQL} is a literal copy of the migration file - keep
 * the two in sync if either changes.
 */
@Testcontainers
class DirectFunctionInvocationIdentityBackfillTest {

    private static final String V16_BACKFILL_SQL = """
            ALTER TABLE invocations
            ALTER COLUMN flow_id DROP NOT NULL,
            ALTER COLUMN flow_key DROP NOT NULL,
            ALTER COLUMN flow_version_id DROP NOT NULL;

            UPDATE invocations
            SET function_id = flow_id,
                function_key = flow_key,
                function_version_id = flow_version_id,
                flow_id = NULL,
                flow_key = NULL,
                flow_version_id = NULL
            WHERE kind = 'DIRECT_FUNCTION'
              AND flow_id IS NOT NULL
            """;

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("funchole")
            .withUsername("funchole")
            .withPassword("funchole");

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = dataSource();
        try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute("drop table if exists invocations");
            statement.execute("""
                    create table invocations (
                        id UUID primary key,
                        kind VARCHAR(50) not null,
                        flow_id UUID not null,
                        flow_key VARCHAR(150) not null,
                        flow_version_id UUID not null,
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
    }

    @Test
    void backfillsLegacyDirectFunctionRowFromFlowColumnsIntoFunctionColumns() throws Exception {
        UUID invocationId = UUID.randomUUID();
        UUID functionId = UUID.randomUUID();
        UUID functionVersionId = UUID.randomUUID();
        insertLegacyDirectFunctionRow(invocationId, functionId, "fn_checkout", functionVersionId);

        runBackfillMigration();

        try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(identityColumnsQuery(invocationId))
        ) {
            resultSet.next();
            assertNull(resultSet.getObject("flow_id"));
            assertNull(resultSet.getObject("flow_key"));
            assertNull(resultSet.getObject("flow_version_id"));
            assertEquals(functionId, resultSet.getObject("function_id", UUID.class));
            assertEquals("fn_checkout", resultSet.getString("function_key"));
            assertEquals(functionVersionId, resultSet.getObject("function_version_id", UUID.class));
        }
    }

    @Test
    void existingFlowRowIsUnchangedByBackfill() throws Exception {
        UUID invocationId = UUID.randomUUID();
        UUID flowId = UUID.randomUUID();
        UUID flowVersionId = UUID.randomUUID();
        insertFlowRow(invocationId, flowId, "flw_checkout", flowVersionId);

        runBackfillMigration();

        try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(identityColumnsQuery(invocationId))
        ) {
            resultSet.next();
            assertEquals(flowId, resultSet.getObject("flow_id", UUID.class));
            assertEquals("flw_checkout", resultSet.getString("flow_key"));
            assertEquals(flowVersionId, resultSet.getObject("flow_version_id", UUID.class));
            assertNull(resultSet.getObject("function_id"));
            assertNull(resultSet.getObject("function_key"));
            assertNull(resultSet.getObject("function_version_id"));
        }
    }

    @Test
    void migratedDirectInvocationIsReadableByRegistryAndInspectionService() {
        UUID invocationId = UUID.randomUUID();
        UUID functionId = UUID.randomUUID();
        UUID functionVersionId = UUID.randomUUID();
        insertLegacyDirectFunctionRow(invocationId, functionId, "fn_checkout", functionVersionId);

        runBackfillMigration();

        JdbcInvocationRegistry registry = new JdbcInvocationRegistry(dataSource);
        InvocationInspectionService inspectionService = new InvocationInspectionService(registry);

        Invocation invocation = registry.findById(invocationId).orElseThrow();
        assertEquals(InvocationKind.DIRECT_FUNCTION, invocation.kind());
        assertEquals(functionVersionId, invocation.functionVersionId());
        assertEquals(functionId, invocation.functionId());
        assertEquals("fn_checkout", invocation.functionKey());
        assertNull(invocation.flowId());
        assertNull(invocation.flowKey());
        assertNull(invocation.flowVersionId());

        InvocationInspection inspection = inspectionService.inspect(invocationId);
        assertEquals(functionVersionId, inspection.functionVersionId());
        assertNull(inspection.flowId());
        assertNull(inspection.flowKey());
        assertNull(inspection.flowVersionId());
    }

    private String identityColumnsQuery(UUID invocationId) {
        return "select flow_id, flow_key, flow_version_id, function_id, function_key, function_version_id "
                + "from invocations where id = '" + invocationId + "'";
    }

    private void runBackfillMigration() {
        try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute(V16_BACKFILL_SQL);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to run backfill migration", exception);
        }
    }

    private void insertLegacyDirectFunctionRow(UUID invocationId, UUID functionId, String functionKey, UUID functionVersionId) {
        try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute("""
                    insert into invocations (id, kind, flow_id, flow_key, flow_version_id, status)
                    values ('%s', 'DIRECT_FUNCTION', '%s', '%s', '%s', 'PENDING')
                    """.formatted(invocationId, functionId, functionKey, functionVersionId));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to insert legacy direct-function test row", exception);
        }
    }

    private void insertFlowRow(UUID invocationId, UUID flowId, String flowKey, UUID flowVersionId) {
        try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute("""
                    insert into invocations (id, kind, flow_id, flow_key, flow_version_id, status)
                    values ('%s', 'FLOW', '%s', '%s', '%s', 'PENDING')
                    """.formatted(invocationId, flowId, flowKey, flowVersionId));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to insert flow test row", exception);
        }
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }
}
