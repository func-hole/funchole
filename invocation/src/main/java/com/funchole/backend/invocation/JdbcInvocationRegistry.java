package com.funchole.backend.invocation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcInvocationRegistry implements InvocationRegistry {

    private final DataSource dataSource;

    public JdbcInvocationRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Invocation create(CreateInvocationRequest request) {
        UUID invocationId = UUID.randomUUID();
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        insert into invocations (
                            id,
                            flow_id,
                            flow_key,
                            flow_version_id,
                            status,
                            input_payload
                        )
                        values (?, ?, ?, ?, ?, ?)
                        returning id, flow_id, flow_key, flow_version_id, status, input_payload, created_at, updated_at
                        """)
        ) {
            statement.setObject(1, invocationId);
            statement.setObject(2, request.flowId());
            statement.setString(3, request.flowKey());
            statement.setObject(4, request.flowVersionId());
            statement.setString(5, InvocationStatus.PENDING.name());
            statement.setObject(6, request.inputPayload(), Types.OTHER);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return toInvocation(resultSet);
                }
            }
            throw new IllegalStateException("Invocation insert did not return a row");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create invocation", exception);
        }
    }

    @Override
    public Optional<Invocation> findById(UUID invocationId) {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        select id, flow_id, flow_key, flow_version_id, status, input_payload, created_at, updated_at
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
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
