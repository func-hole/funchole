package com.funchole.backend.dispatcher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcInvocationStepExecutionRegistry implements InvocationStepExecutionRegistry {

    private static final int INITIAL_ATTEMPT = 1;

    private final DataSource dataSource;

    public JdbcInvocationStepExecutionRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public InvocationStepExecution createOrGetReadyExecution(DispatchableStep dispatchableStep) {
        try (Connection connection = dataSource.getConnection()) {
            InvocationStepExecution inserted = insertIfAbsent(connection, dispatchableStep);
            if (inserted != null) {
                return inserted;
            }
            return findExisting(connection, dispatchableStep.invocationId(), dispatchableStep.stepId(), INITIAL_ATTEMPT)
                    .orElseThrow(() -> new IllegalStateException(
                            "Step execution insert conflicted but no existing record was found for invocationId="
                                    + dispatchableStep.invocationId() + ", stepId=" + dispatchableStep.stepId()));
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to create or get step execution for invocationId=" + dispatchableStep.invocationId()
                            + ", stepId=" + dispatchableStep.stepId(),
                    exception
            );
        }
    }

    private InvocationStepExecution insertIfAbsent(Connection connection, DispatchableStep dispatchableStep) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into invocation_step_executions (
                    id,
                    invocation_id,
                    flow_id,
                    flow_version_id,
                    step_id,
                    position,
                    component_type,
                    component_id,
                    component_version_id,
                    status,
                    attempt
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (invocation_id, step_id, attempt) do nothing
                returning id, invocation_id, flow_id, flow_version_id, step_id, position, component_type,
                    component_id, component_version_id, status, attempt, created_at, updated_at
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, dispatchableStep.invocationId());
            statement.setObject(3, dispatchableStep.flowId());
            statement.setObject(4, dispatchableStep.flowVersionId());
            statement.setObject(5, dispatchableStep.stepId());
            statement.setInt(6, dispatchableStep.position());
            statement.setString(7, dispatchableStep.componentType());
            statement.setObject(8, dispatchableStep.componentId());
            statement.setObject(9, dispatchableStep.componentVersionId());
            statement.setString(10, InvocationStepExecutionStatus.READY.name());
            statement.setInt(11, INITIAL_ATTEMPT);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return toStepExecution(resultSet);
                }
            }
            return null;
        }
    }

    private Optional<InvocationStepExecution> findExisting(
            Connection connection,
            UUID invocationId,
            UUID stepId,
            int attempt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select id, invocation_id, flow_id, flow_version_id, step_id, position, component_type,
                    component_id, component_version_id, status, attempt, created_at, updated_at
                from invocation_step_executions
                where invocation_id = ? and step_id = ? and attempt = ?
                """)) {
            statement.setObject(1, invocationId);
            statement.setObject(2, stepId);
            statement.setInt(3, attempt);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(toStepExecution(resultSet));
                }
            }
            return Optional.empty();
        }
    }

    private InvocationStepExecution toStepExecution(ResultSet resultSet) throws SQLException {
        return new InvocationStepExecution(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("invocation_id", UUID.class),
                resultSet.getObject("flow_id", UUID.class),
                resultSet.getObject("flow_version_id", UUID.class),
                resultSet.getObject("step_id", UUID.class),
                resultSet.getInt("position"),
                resultSet.getString("component_type"),
                resultSet.getObject("component_id", UUID.class),
                resultSet.getObject("component_version_id", UUID.class),
                InvocationStepExecutionStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
