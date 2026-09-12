package com.funchole.backend.invocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.invocationcontract.DirectInvocationRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcInvocationRegistry implements InvocationRegistry {

    private static final String SELECT_COLUMNS = """
            id, kind, flow_id, flow_key, flow_version_id, function_id, function_key, function_version_id,
            status, input_payload, dependency_snapshot, result, error, created_at, updated_at, completed_at
            """;

    /**
     * The single step key written into the dependency snapshot of every
     * direct FunctionVersion invocation. Purely internal execution-snapshot
     * detail - never exposed via invocation-contract, and never used to
     * classify an Invocation (the durable {@code kind} column owns that).
     */
    private static final String DIRECT_INVOCATION_STEP_KEY = "invoke-function";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final InvocationEventPublisher eventPublisher;

    public JdbcInvocationRegistry(DataSource dataSource) {
        this(dataSource, new NoopInvocationEventPublisher());
    }

    public JdbcInvocationRegistry(DataSource dataSource, InvocationEventPublisher eventPublisher) {
        this.dataSource = dataSource;
        this.objectMapper = new ObjectMapper();
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Invocation create(CreateInvocationRequest request) {
        UUID invocationId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            InvocationSnapshot snapshot = resolveSnapshot(connection, request);
            String dependencySnapshot = serializeSnapshot(snapshot);
            Invocation invocation = insertInvocation(
                    connection,
                    invocationId,
                    InvocationKind.FLOW,
                    request.flowId(),
                    request.flowKey(),
                    request.flowVersionId(),
                    null,
                    null,
                    null,
                    request.inputPayload(),
                    dependencySnapshot
            );
            eventPublisher.publishInvocationReady(invocation);
            return invocation;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create invocation", exception);
        }
    }

    @Override
    public Invocation createDirectInvocation(DirectInvocationRequest request) {
        UUID invocationId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            InvocationSnapshot snapshot = directInvocationSnapshot(request);
            String dependencySnapshot = serializeSnapshot(snapshot);
            Invocation invocation = insertInvocation(
                    connection,
                    invocationId,
                    InvocationKind.DIRECT_FUNCTION,
                    null,
                    null,
                    null,
                    request.functionId(),
                    request.functionKey(),
                    request.functionVersionId(),
                    request.inputPayload(),
                    dependencySnapshot
            );
            eventPublisher.publishInvocationReady(invocation);
            return invocation;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create direct invocation", exception);
        }
    }

    /**
     * Builds the immutable dependency snapshot for a direct FunctionVersion
     * invocation WITHOUT any Flow-table resolution. This deliberately reuses
     * the regular flow-shaped snapshot model (root flow + one ordered
     * FUNCTION step): the Dispatcher, planner, and runtime execution path
     * need no code changes, and the snapshot still proves exactly which
     * componentVersionId (the pinned FunctionVersion.id) was executed.
     */
    private InvocationSnapshot directInvocationSnapshot(DirectInvocationRequest request) {
        InvocationStepSnapshot invokeFunctionStep = new InvocationStepSnapshot(
                UUID.randomUUID(),
                DIRECT_INVOCATION_STEP_KEY,
                "FUNCTION",
                1,
                request.functionId(),
                request.functionVersionId(),
                null
        );
        InvocationFlowSnapshot directFlow = new InvocationFlowSnapshot(
                request.functionId(),
                request.functionKey(),
                request.functionVersionId(),
                1,
                "ADOPTED",
                request.runtimeType(),
                null,
                List.of(invokeFunctionStep)
        );
        return new InvocationSnapshot(
                request.functionId(),
                request.functionKey(),
                request.functionVersionId(),
                List.of(directFlow)
        );
    }

    private Invocation insertInvocation(
            Connection connection,
            UUID invocationId,
            InvocationKind kind,
            UUID flowId,
            String flowKey,
            UUID flowVersionId,
            UUID functionId,
            String functionKey,
            UUID functionVersionId,
            String inputPayload,
            String dependencySnapshot
    ) throws SQLException {
        // Identity invariants per kind: a FLOW invocation carries only Flow
        // identity, a DIRECT_FUNCTION invocation carries only function
        // identity. Wrong/missing identity for the declared kind fails
        // clearly instead of silently writing a semantically misleading row.
        if (kind == InvocationKind.FLOW) {
            if (flowId == null || flowKey == null || flowVersionId == null) {
                throw new IllegalArgumentException("A FLOW invocation requires flowId, flowKey, and flowVersionId");
            }
            if (functionId != null || functionKey != null || functionVersionId != null) {
                throw new IllegalArgumentException("A FLOW invocation must not carry function identity");
            }
        } else if (kind == InvocationKind.DIRECT_FUNCTION) {
            if (functionId == null || functionKey == null || functionVersionId == null) {
                throw new IllegalArgumentException(
                        "A DIRECT_FUNCTION invocation requires functionId, functionKey, and functionVersionId");
            }
            if (flowId != null || flowKey != null || flowVersionId != null) {
                throw new IllegalArgumentException("A DIRECT_FUNCTION invocation must not carry Flow identity");
            }
        } else {
            throw new IllegalArgumentException("Unknown invocation kind: " + kind);
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                    insert into invocations (
                        id,
                        kind,
                        flow_id,
                        flow_key,
                        flow_version_id,
                        function_id,
                        function_key,
                        function_version_id,
                        status,
                        input_payload,
                        dependency_snapshot
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    returning
                    """ + SELECT_COLUMNS)) {
            statement.setObject(1, invocationId);
            statement.setString(2, kind.name());
            statement.setObject(3, flowId);
            statement.setString(4, flowKey);
            statement.setObject(5, flowVersionId);
            statement.setObject(6, functionId);
            statement.setString(7, functionKey);
            statement.setObject(8, functionVersionId);
            statement.setString(9, InvocationStatus.PENDING.name());
            statement.setObject(10, inputPayload, Types.OTHER);
            statement.setObject(11, dependencySnapshot, Types.OTHER);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return toInvocation(resultSet);
                }
                throw new IllegalStateException("Invocation insert did not return a row");
            }
        }
    }

    @Override
    public Optional<Invocation> findById(UUID invocationId) {
        try (Connection connection = dataSource.getConnection()) {
            return findById(connection, invocationId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to retrieve invocation " + invocationId, exception);
        }
    }

    @Override
    public InvocationTransition markCompleted(UUID invocationId, String result) {
        return markTerminal(invocationId, InvocationStatus.COMPLETED, result, null);
    }

    @Override
    public InvocationTransition markFailed(UUID invocationId, String error) {
        return markTerminal(invocationId, InvocationStatus.FAILED, null, error);
    }

    private InvocationTransition markTerminal(UUID invocationId, InvocationStatus terminalStatus, String result, String error) {
        try (Connection connection = dataSource.getConnection()) {
            String canonicalResult = canonicalizeJson(connection, result);
            String canonicalError = canonicalizeJson(connection, error);
            Invocation current = findById(connection, invocationId)
                    .orElseThrow(() -> new IllegalStateException("Invocation not found: " + invocationId));
            if (current.status() == terminalStatus) {
                if (Objects.equals(current.result(), canonicalResult) && Objects.equals(current.error(), canonicalError)) {
                    return new InvocationTransition(current, false);
                }
                throw new IllegalStateException("Conflicting terminal payload for invocation " + invocationId);
            }
            if (current.status() == InvocationStatus.COMPLETED || current.status() == InvocationStatus.FAILED) {
                throw new IllegalStateException("Cannot overwrite terminal invocation " + invocationId
                        + " from " + current.status() + " to " + terminalStatus);
            }
            if (current.status() != InvocationStatus.PENDING) {
                throw new IllegalStateException("Cannot mark invocation " + invocationId
                        + " terminal from status " + current.status());
            }

            Invocation updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    update invocations
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
                statement.setObject(4, invocationId);
                statement.setString(5, InvocationStatus.PENDING.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        Invocation latest = findById(connection, invocationId)
                                .orElseThrow(() -> new IllegalStateException("Invocation disappeared: " + invocationId));
                        return new InvocationTransition(latest, false);
                    }
                    updated = toInvocation(resultSet);
                }
            }

            if (terminalStatus == InvocationStatus.COMPLETED) {
                eventPublisher.publishInvocationCompleted(updated);
            } else {
                eventPublisher.publishInvocationFailed(updated);
            }
            return new InvocationTransition(updated, true);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to mark terminal invocation: " + invocationId, exception);
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

    private Optional<Invocation> findById(Connection connection, UUID invocationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select
                """ + SELECT_COLUMNS + """
                from invocations
                where id = ?
                """)) {
            statement.setObject(1, invocationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(toInvocation(resultSet));
                }
            }
            return Optional.empty();
        }
    }

    private Invocation toInvocation(ResultSet resultSet) throws SQLException {
        return new Invocation(
                resultSet.getObject("id", UUID.class),
                parseKind(resultSet.getString("kind")),
                resultSet.getObject("flow_id", UUID.class),
                resultSet.getString("flow_key"),
                resultSet.getObject("flow_version_id", UUID.class),
                resultSet.getObject("function_id", UUID.class),
                resultSet.getString("function_key"),
                resultSet.getObject("function_version_id", UUID.class),
                InvocationStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("input_payload"),
                resultSet.getString("dependency_snapshot"),
                resultSet.getString("result"),
                resultSet.getString("error"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                resultSet.getObject("completed_at", OffsetDateTime.class)
        );
    }

    /**
     * The {@code kind} column is never allowed to silently become FLOW: a
     * missing value or an unrecognized string both fail clearly here rather
     * than being reinterpreted.
     */
    private InvocationKind parseKind(String kind) {
        if (kind == null) {
            throw new IllegalStateException("Invocation kind is missing");
        }
        try {
            return InvocationKind.valueOf(kind);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown invocation kind: " + kind, exception);
        }
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
