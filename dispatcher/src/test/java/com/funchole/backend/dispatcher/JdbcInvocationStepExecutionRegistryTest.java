package com.funchole.backend.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
class JdbcInvocationStepExecutionRegistryTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("funchole")
            .withUsername("funchole")
            .withPassword("funchole");

    private JdbcInvocationStepExecutionRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()
        ) {
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
        registry = new JdbcInvocationStepExecutionRegistry(dataSource);
    }

    @Test
    void createsReadyExecutionAndPreservesPinnedExecutionTarget() {
        DispatchableStep dispatchableStep = dispatchableStep(UUID.randomUUID(), UUID.randomUUID());

        InvocationStepExecution execution = registry.createOrGetReadyExecution(dispatchableStep);

        assertNotNull(execution.id());
        assertEquals(InvocationStepExecutionStatus.READY, execution.status());
        assertEquals(1, execution.attempt());
        assertEquals(dispatchableStep.invocationId(), execution.invocationId());
        assertEquals(dispatchableStep.flowId(), execution.flowId());
        assertEquals(dispatchableStep.flowVersionId(), execution.flowVersionId());
        assertEquals(dispatchableStep.stepId(), execution.stepId());
        assertEquals(dispatchableStep.position(), execution.position());
        assertEquals(dispatchableStep.componentType(), execution.componentType());
        assertEquals(dispatchableStep.componentId(), execution.componentId());
        assertEquals(dispatchableStep.componentVersionId(), execution.componentVersionId());
        assertNotNull(execution.createdAt());
        assertNotNull(execution.updatedAt());
    }

    @Test
    void redeliveryOfSameInvocationAndStepReturnsExistingRecordWithoutDuplicate() throws Exception {
        UUID invocationId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        DispatchableStep dispatchableStep = dispatchableStep(invocationId, stepId);

        InvocationStepExecution first = registry.createOrGetReadyExecution(dispatchableStep);
        InvocationStepExecution second = registry.createOrGetReadyExecution(dispatchableStep);

        assertEquals(first.id(), second.id());
        assertEquals(1, countExecutions(invocationId, stepId, 1));
    }

    @Test
    void differentInvocationsForSameStepGetSeparateExecutionRecords() {
        UUID stepId = UUID.randomUUID();
        DispatchableStep first = dispatchableStep(UUID.randomUUID(), stepId);
        DispatchableStep second = dispatchableStep(UUID.randomUUID(), stepId);

        InvocationStepExecution firstExecution = registry.createOrGetReadyExecution(first);
        InvocationStepExecution secondExecution = registry.createOrGetReadyExecution(second);

        assertEquals(2, countExecutions(stepId));
        assertEquals(first.invocationId(), firstExecution.invocationId());
        assertEquals(second.invocationId(), secondExecution.invocationId());
        assertEquals(1, firstExecution.attempt());
        assertEquals(1, secondExecution.attempt());
    }

    @Test
    void attemptTwoCanCoexistWithAttemptOneUnderTheUniqueConstraint() throws Exception {
        UUID invocationId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        DispatchableStep dispatchableStep = dispatchableStep(invocationId, stepId);
        registry.createOrGetReadyExecution(dispatchableStep);

        insertExecutionRow(dispatchableStep, 2);

        assertEquals(1, countExecutions(invocationId, stepId, 1));
        assertEquals(1, countExecutions(invocationId, stepId, 2));
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    private DispatchableStep dispatchableStep(UUID invocationId, UUID stepId) {
        return new DispatchableStep(
                invocationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                stepId,
                1,
                "validate-orders-request",
                "FUNCTION",
                UUID.randomUUID(),
                UUID.randomUUID()
        );
    }

    private void insertExecutionRow(DispatchableStep dispatchableStep, int attempt) throws Exception {
        try (
                Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute("""
                    insert into invocation_step_executions (
                        id, invocation_id, flow_id, flow_version_id, step_id, position,
                        component_type, component_id, component_version_id, status, attempt
                    )
                    values (
                        '%s', '%s', '%s', '%s', '%s', %s,
                        '%s', '%s', '%s', 'READY', %s
                    )
                    """.formatted(
                    UUID.randomUUID(),
                    dispatchableStep.invocationId(),
                    dispatchableStep.flowId(),
                    dispatchableStep.flowVersionId(),
                    dispatchableStep.stepId(),
                    dispatchableStep.position(),
                    dispatchableStep.componentType(),
                    dispatchableStep.componentId(),
                    dispatchableStep.componentVersionId(),
                    attempt
            ));
        }
    }

    private int countExecutions(UUID stepId) {
        try (
                Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "select count(*) from invocation_step_executions where step_id = '" + stepId + "'")
        ) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int countExecutions(UUID invocationId, UUID stepId, int attempt) {
        try (
                Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "select count(*) from invocation_step_executions where invocation_id = '" + invocationId
                                + "' and step_id = '" + stepId + "' and attempt = " + attempt)
        ) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
