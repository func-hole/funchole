package com.funchole.backend.invocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcInvocationRegistry implements InvocationRegistry {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcInvocationRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Invocation create(CreateInvocationRequest request) {
        UUID invocationId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            InvocationSnapshot snapshot = resolveSnapshot(connection, request);
            String dependencySnapshot = serializeSnapshot(snapshot);

            try (PreparedStatement statement = connection.prepareStatement("""
                        insert into invocations (
                            id,
                            flow_id,
                            flow_key,
                            flow_version_id,
                            status,
                            input_payload,
                            dependency_snapshot
                        )
                        values (?, ?, ?, ?, ?, ?, ?)
                        returning id, flow_id, flow_key, flow_version_id, status, input_payload, dependency_snapshot, created_at, updated_at
                        """)) {
                statement.setObject(1, invocationId);
                statement.setObject(2, request.flowId());
                statement.setString(3, request.flowKey());
                statement.setObject(4, request.flowVersionId());
                statement.setString(5, InvocationStatus.PENDING.name());
                statement.setObject(6, request.inputPayload(), Types.OTHER);
                statement.setObject(7, dependencySnapshot, Types.OTHER);

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return toInvocation(resultSet);
                    }
                }
                throw new IllegalStateException("Invocation insert did not return a row");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create invocation", exception);
        }
    }

    @Override
    public Optional<Invocation> findById(UUID invocationId) {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        select id, flow_id, flow_key, flow_version_id, status, input_payload, dependency_snapshot, created_at, updated_at
                        from invocations
                        where id = ?
                        """)
        ) {
            statement.setObject(1, invocationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(toInvocation(resultSet));
                }
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to retrieve invocation " + invocationId, exception);
        }
    }

    private Invocation toInvocation(ResultSet resultSet) throws SQLException {
        return new Invocation(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("flow_id", UUID.class),
                resultSet.getString("flow_key"),
                resultSet.getObject("flow_version_id", UUID.class),
                InvocationStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("input_payload"),
                resultSet.getString("dependency_snapshot"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private InvocationSnapshot resolveSnapshot(Connection connection, CreateInvocationRequest request) {
        ArrayDeque<UUID> resolutionStack = new ArrayDeque<>();
        List<InvocationFlowSnapshot> flows = new ArrayList<>();
        resolveFlowVersion(connection, request.flowId(), request.flowVersionId(), resolutionStack, flows);
        return new InvocationSnapshot(request.flowId(), request.flowKey(), request.flowVersionId(), List.copyOf(flows));
    }

    private void resolveFlowVersion(
            Connection connection,
            UUID flowId,
            UUID flowVersionId,
            ArrayDeque<UUID> resolutionStack,
            List<InvocationFlowSnapshot> flows
    ) {
        if (resolutionStack.contains(flowVersionId)) {
            throw new DependencyGraphResolutionException("Circular sub-flow dependency detected at flow version " + flowVersionId);
        }

        resolutionStack.push(flowVersionId);
        FlowVersionRecord flowVersion = loadFlowVersion(connection, flowId, flowVersionId);
        List<InvocationStepSnapshot> steps = loadSteps(connection, flowVersionId);
        flows.add(new InvocationFlowSnapshot(
                flowVersion.flowId(),
                flowVersion.flowKey(),
                flowVersion.flowVersionId(),
                flowVersion.version(),
                flowVersion.status(),
                flowVersion.runtime(),
                flowVersion.metadata(),
                steps
        ));

        for (InvocationStepSnapshot step : steps) {
            if ("SUB_FLOW".equalsIgnoreCase(step.componentType())) {
                resolveFlowVersion(connection, step.componentId(), step.componentVersionId(), resolutionStack, flows);
            }
        }

        resolutionStack.pop();
    }

    private FlowVersionRecord loadFlowVersion(Connection connection, UUID flowId, UUID flowVersionId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                select
                    f.id as flow_id,
                    f.flow_key,
                    fv.id as flow_version_id,
                    fv.version,
                    fv.status,
                    fv.runtime,
                    fv.metadata
                from flow_versions fv
                join flows f on f.id = fv.flow_id
                where fv.flow_id = ?
                  and fv.id = ?
                """)) {
            statement.setObject(1, flowId);
            statement.setObject(2, flowVersionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new FlowVersionRecord(
                            resultSet.getObject("flow_id", UUID.class),
                            resultSet.getString("flow_key"),
                            resultSet.getObject("flow_version_id", UUID.class),
                            resultSet.getInt("version"),
                            resultSet.getString("status"),
                            resultSet.getString("runtime"),
                            resultSet.getString("metadata")
                    );
                }
            }
        } catch (SQLException exception) {
            throw new DependencyGraphResolutionException("Failed to load flow version " + flowVersionId, exception);
        }
        throw new DependencyGraphResolutionException("Flow version not found: flowId=" + flowId + ", flowVersionId=" + flowVersionId);
    }

    private List<InvocationStepSnapshot> loadSteps(Connection connection, UUID flowVersionId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                select id, step_key, component_type, position, component_id, component_version_id, metadata
                from flow_steps
                where flow_version_id = ?
                order by position asc
                """)) {
            statement.setObject(1, flowVersionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<InvocationStepSnapshot> steps = new ArrayList<>();
                while (resultSet.next()) {
                    steps.add(new InvocationStepSnapshot(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("step_key"),
                            resultSet.getString("component_type"),
                            resultSet.getInt("position"),
                            resultSet.getObject("component_id", UUID.class),
                            resultSet.getObject("component_version_id", UUID.class),
                            resultSet.getString("metadata")
                    ));
                }
                return List.copyOf(steps);
            }
        } catch (SQLException exception) {
            throw new DependencyGraphResolutionException("Failed to load steps for flow version " + flowVersionId, exception);
        }
    }

    private String serializeSnapshot(InvocationSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new DependencyGraphResolutionException("Failed to serialize invocation dependency snapshot", exception);
        }
    }

    private record FlowVersionRecord(
            UUID flowId,
            String flowKey,
            UUID flowVersionId,
            int version,
            String status,
            String runtime,
            String metadata
    ) {
    }
}
