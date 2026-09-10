package com.funchole.backend.dispatcher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcInvocationStepExecutionRegistry implements InvocationStepExecutionRegistry {

    private static final int INITIAL_ATTEMPT = 1;
    private static final String SELECT_COLUMNS = """
            id, invocation_id, flow_id, flow_version_id, step_id, position, component_type,
            component_id, component_version_id, runtime_type, runtime_instance_id, status, attempt, result, error,
            created_at, updated_at, started_at, completed_at
            """;

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
                    runtime_type,
                    status,
                    attempt
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (invocation_id, step_id, attempt) do nothing
                returning
                """ + SELECT_COLUMNS)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, dispatchableStep.invocationId());
            statement.setObject(3, dispatchableStep.flowId());
            statement.setObject(4, dispatchableStep.flowVersionId());
            statement.setObject(5, dispatchableStep.stepId());
            statement.setInt(6, dispatchableStep.position());
            statement.setString(7, dispatchableStep.componentType());
            statement.setObject(8, dispatchableStep.componentId());
            statement.setObject(9, dispatchableStep.componentVersionId());
            statement.setString(10, dispatchableStep.runtimeType());
            statement.setString(11, InvocationStepExecutionStatus.READY.name());
            statement.setInt(12, INITIAL_ATTEMPT);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return toStepExecution(resultSet);
                }
            }
            return null;
        }
    }

    @Override
    public InvocationStepExecution markRunning(UUID executionId, String runtimeInstanceId) {
        if (runtimeInstanceId == null || runtimeInstanceId.isBlank()) {
            throw new IllegalArgumentException("runtimeInstanceId is required");
        }
        try (Connection connection = dataSource.getConnection()) {
            InvocationStepExecution current = findById(connection, executionId)
                    .orElseThrow(() -> new IllegalStateException("Step execution not found: " + executionId));
            if (current.status() == InvocationStepExecutionStatus.RUNNING) {
                if (!runtimeInstanceId.equals(current.runtimeInstanceId())) {
                    throw new IllegalStateException("Step execution " + executionId
                            + " is already RUNNING on runtime " + current.runtimeInstanceId());
                }
                return current;
            }
            if (current.status() != InvocationStepExecutionStatus.READY) {
                throw new IllegalStateException("Cannot mark step execution " + executionId
                        + " RUNNING from status " + current.status());
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    update invocation_step_executions
                    set status = ?,
                        runtime_instance_id = ?,
                        started_at = coalesce(started_at, CURRENT_TIMESTAMP),
                        updated_at = CURRENT_TIMESTAMP
                    where id = ?
                      and status = ?
                    returning
                    """ + SELECT_COLUMNS)) {
                statement.setString(1, InvocationStepExecutionStatus.RUNNING.name());
                statement.setString(2, runtimeInstanceId);
                statement.setObject(3, executionId);
                statement.setString(4, InvocationStepExecutionStatus.READY.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return toStepExecution(resultSet);
                    }
                }
            }
            return findById(connection, executionId)
                    .orElseThrow(() -> new IllegalStateException("Step execution disappeared: " + executionId));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to mark step execution RUNNING: " + executionId, exception);
        }
    }

    @Override
    public InvocationStepExecutionTransition markCompleted(UUID executionId, RuntimeExecutionResult result) {
        if (result.terminalType() != RuntimeExecutionTerminalType.RESULT) {
            throw new IllegalArgumentException("Runtime RESULT is required to mark completed");
        }
        return markTerminal(executionId, InvocationStepExecutionStatus.COMPLETED, result.output(), null);
    }

    @Override
    public InvocationStepExecutionTransition markFailed(UUID executionId, RuntimeExecutionResult result) {
        if (result.terminalType() != RuntimeExecutionTerminalType.ERROR) {
            throw new IllegalArgumentException("Runtime ERROR is required to mark failed");
        }
        return markTerminal(executionId, InvocationStepExecutionStatus.FAILED, null, serializeError(result.error()));
    }

    public Optional<InvocationStepExecution> findById(UUID executionId) {
        try (Connection connection = dataSource.getConnection()) {
            return findById(connection, executionId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to find step execution: " + executionId, exception);
        }
    }

    private InvocationStepExecutionTransition markTerminal(
            UUID executionId,
            InvocationStepExecutionStatus terminalStatus,
            String result,
            String error
    ) {
        try (Connection connection = dataSource.getConnection()) {
            String canonicalResult = canonicalizeJson(connection, result);
            String canonicalError = canonicalizeJson(connection, error);
            InvocationStepExecution current = findById(connection, executionId)
                    .orElseThrow(() -> new IllegalStateException("Step execution not found: " + executionId));
            if (current.status() == terminalStatus) {
                if (Objects.equals(current.result(), canonicalResult) && Objects.equals(current.error(), canonicalError)) {
                    return new InvocationStepExecutionTransition(current, false);
                }
                throw new IllegalStateException("Conflicting terminal payload for step execution " + executionId);
            }
            if (current.status() == InvocationStepExecutionStatus.COMPLETED
                    || current.status() == InvocationStepExecutionStatus.FAILED) {
                throw new IllegalStateException("Cannot overwrite terminal step execution " + executionId
                        + " from " + current.status() + " to " + terminalStatus);
            }
            if (current.status() != InvocationStepExecutionStatus.RUNNING) {
                throw new IllegalStateException("Cannot mark step execution " + executionId
                        + " terminal from status " + current.status());
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    update invocation_step_executions
                    set status = ?,
                        result = ?,
                        error = ?,
                        completed_at = coalesce(completed_at, CURRENT_TIMESTAMP),
                        updated_at = CURRENT_TIMESTAMP
                    where id = ?
                      and status = ?
                    returning
                    """ + SELECT_COLUMNS)) {
                statement.setString(1, terminalStatus.name());
                statement.setObject(2, canonicalResult, Types.OTHER);
                statement.setObject(3, canonicalError, Types.OTHER);
                statement.setObject(4, executionId);
                statement.setString(5, InvocationStepExecutionStatus.RUNNING.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return new InvocationStepExecutionTransition(toStepExecution(resultSet), true);
                    }
                }
            }
            InvocationStepExecution latest = findById(connection, executionId)
                    .orElseThrow(() -> new IllegalStateException("Step execution disappeared: " + executionId));
            return new InvocationStepExecutionTransition(latest, false);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to mark terminal step execution: " + executionId, exception);
        }
    }

    private String canonicalizeJson(Connection connection, String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("select ?::jsonb::text")) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString(1);
                }
            }
        }
        throw new IllegalStateException("Failed to canonicalize JSON payload");
    }

    private Optional<InvocationStepExecution> findExisting(
            Connection connection,
            UUID invocationId,
            UUID stepId,
            int attempt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select
                """ + SELECT_COLUMNS + """
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

    private Optional<InvocationStepExecution> findById(Connection connection, UUID executionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select
                """ + SELECT_COLUMNS + """
                from invocation_step_executions
                where id = ?
                """)) {
            statement.setObject(1, executionId);
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
                resultSet.getString("runtime_type"),
                resultSet.getString("runtime_instance_id"),
                InvocationStepExecutionStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt"),
                resultSet.getString("result"),
                resultSet.getString("error"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                resultSet.getObject("started_at", OffsetDateTime.class),
                resultSet.getObject("completed_at", OffsetDateTime.class)
        );
    }

    private String serializeError(RuntimeExecutionError error) {
        if (error == null) {
            return null;
        }
        return "{\"code\":\"" + escapeJson(error.code()) + "\",\"message\":\"" + escapeJson(error.message()) + "\"}";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
