package com.funchole.backend.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            statement.execute("drop table if exists invocations");
            statement.execute("""
                    create table invocations (
                        id UUID primary key,
                        flow_id UUID not null,
                        flow_key VARCHAR(150) not null,
                        flow_version_id UUID not null,
                        status VARCHAR(100) not null,
                        input_payload JSONB,
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
        assertNotNull(invocation.createdAt());
        assertNotNull(invocation.updatedAt());

        Invocation retrieved = registry.findById(invocation.invocationId()).orElseThrow();

        assertEquals(invocation.invocationId(), retrieved.invocationId());
        assertEquals(flowId, retrieved.flowId());
        assertEquals("flw_checkout", retrieved.flowKey());
        assertEquals(flowVersionId, retrieved.flowVersionId());
        assertEquals(InvocationStatus.PENDING, retrieved.status());
        assertJsonEquals(inputPayload, retrieved.inputPayload());
    }

    @Test
    void generatesUniqueInvocationIds() {
        CreateInvocationRequest request = new CreateInvocationRequest(
                UUID.randomUUID(),
                "flw_checkout",
                UUID.randomUUID(),
                "{}"
        );

        Invocation first = registry.create(request);
        Invocation second = registry.create(request);

        assertNotEquals(first.invocationId(), second.invocationId());
        assertTrue(registry.findById(first.invocationId()).isPresent());
        assertTrue(registry.findById(second.invocationId()).isPresent());
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
}
