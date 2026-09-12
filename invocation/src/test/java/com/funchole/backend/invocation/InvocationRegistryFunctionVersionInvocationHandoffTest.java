package com.funchole.backend.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.funchole.backend.invocationcontract.DirectInvocationRequest;
import com.funchole.backend.invocationcontract.DirectInvocationResult;
import com.funchole.backend.invocationcontract.FunctionVersionInvocationHandoff;
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

/**
 * Proves {@link InvocationRegistryFunctionVersionInvocationHandoff} - the
 * concrete {@link FunctionVersionInvocationHandoff} that a caller like
 * controlplane is handed across the boundary - reuses the existing durable
 * Invocation creation path exactly: a real {@link InvocationKind#DIRECT_FUNCTION}
 * row with explicit function identity, a real immutable execution snapshot,
 * PENDING as the initial status, and the same ready-event publication every
 * other Invocation goes through. No second execution engine is introduced.
 */
@Testcontainers
class InvocationRegistryFunctionVersionInvocationHandoffTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("funchole")
            .withUsername("funchole")
            .withPassword("funchole");

    private JdbcInvocationRegistry registry;
    private CapturingInvocationEventPublisher eventPublisher;
    private FunctionVersionInvocationHandoff handoff;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute("drop table if exists invocations");
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
        eventPublisher = new CapturingInvocationEventPublisher();
        registry = new JdbcInvocationRegistry(dataSource, eventPublisher);
        handoff = new InvocationRegistryFunctionVersionInvocationHandoff(registry);
    }

    @Test
    void dispatchCreatesADirectFunctionInvocationWithExplicitIdentity() {
        UUID functionId = UUID.randomUUID();
        UUID functionVersionId = UUID.randomUUID();
        DirectInvocationRequest request = new DirectInvocationRequest(
                functionId, "fn_checkout", functionVersionId, "NODE", "{\"path\":\"/orders\"}");

        DirectInvocationResult result = handoff.dispatch(request);

        assertEquals(functionVersionId, result.functionVersionId());
        assertEquals("PENDING", result.initialStatus());

        Invocation persisted = registry.findById(result.invocationId()).orElseThrow();
        assertEquals(InvocationKind.DIRECT_FUNCTION, persisted.kind());
        assertEquals(functionId, persisted.functionId());
        assertEquals("fn_checkout", persisted.functionKey());
        assertEquals(functionVersionId, persisted.functionVersionId());
        assertEquals(InvocationStatus.PENDING, persisted.status());
        assertNull(persisted.flowId());
        assertNull(persisted.flowKey());
        assertNull(persisted.flowVersionId());
    }

    @Test
    void dispatchCreatesAnImmutableExecutionSnapshot() {
        UUID functionId = UUID.randomUUID();
        UUID functionVersionId = UUID.randomUUID();
        DirectInvocationRequest request = new DirectInvocationRequest(
                functionId, "fn_checkout", functionVersionId, "NODE", "{}");

        DirectInvocationResult result = handoff.dispatch(request);

        Invocation persisted = registry.findById(result.invocationId()).orElseThrow();
        assertTrue(persisted.dependencySnapshot() != null && !persisted.dependencySnapshot().isBlank());
    }

    @Test
    void dispatchReusesTheExistingReadyEventPath() {
        DirectInvocationRequest request = new DirectInvocationRequest(
                UUID.randomUUID(), "fn_checkout", UUID.randomUUID(), "NODE", "{}");

        DirectInvocationResult result = handoff.dispatch(request);

        assertEquals(1, eventPublisher.published.size());
        assertEquals(result.invocationId(), eventPublisher.published.get(0).invocationId());
    }

    @Test
    void dispatchFailurePropagatesAsHandoffException() {
        registry = new JdbcInvocationRegistry(null, eventPublisher);
        handoff = new InvocationRegistryFunctionVersionInvocationHandoff(registry);
        DirectInvocationRequest request = new DirectInvocationRequest(
                UUID.randomUUID(), "fn_checkout", UUID.randomUUID(), "NODE", "{}");

        assertThrows(
                FunctionVersionInvocationHandoff.FunctionVersionInvocationDispatchException.class,
                () -> handoff.dispatch(request)
        );
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    private static final class CapturingInvocationEventPublisher implements InvocationEventPublisher {
        private final List<Invocation> published = new ArrayList<>();

        @Override
        public void publishInvocationReady(Invocation invocation) {
            published.add(invocation);
        }

        @Override
        public void publishInvocationCompleted(Invocation invocation) {
        }

        @Override
        public void publishInvocationFailed(Invocation invocation) {
        }
    }
}
