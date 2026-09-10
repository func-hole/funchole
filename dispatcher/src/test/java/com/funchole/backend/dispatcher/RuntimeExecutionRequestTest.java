package com.funchole.backend.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.funchole.backend.invocation.Invocation;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeExecutionRequestTest {

    private static final UUID EXECUTION_ID = UUID.fromString("aa000000-0000-0000-0000-000000000001");
    private static final UUID INVOCATION_ID = UUID.fromString("bb000000-0000-0000-0000-000000000001");
    private static final UUID FLOW_ID = UUID.fromString("10000000-0000-0000-0000-000000000101");
    private static final UUID FLOW_VERSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000101");

    @Test
    void derivesRequestEntirelyFromPinnedStepExecutionAndInvocationInput() {
        InvocationStepExecution stepExecution = new InvocationStepExecution(
                EXECUTION_ID,
                INVOCATION_ID,
                FLOW_ID,
                FLOW_VERSION_ID,
                UUID.fromString("c0000000-0000-0000-0000-000000000001"),
                1,
                "FUNCTION",
                UUID.fromString("88888888-8888-8888-8888-888888888861"),
                UUID.fromString("99999999-9999-9999-9999-999999999861"),
                "NODE",
                null,
                InvocationStepExecutionStatus.READY,
                1,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null,
                null
        );
        Invocation invocation = new Invocation(
                INVOCATION_ID,
                FLOW_ID,
                "flw_orders_list",
                FLOW_VERSION_ID,
                com.funchole.backend.invocation.InvocationStatus.PENDING,
                "{\"path\":\"/orders\"}",
                "{}",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        RuntimeExecutionRequest request = RuntimeExecutionRequest.fromStepExecution(stepExecution, invocation);

        assertEquals(EXECUTION_ID, request.executionId());
        assertEquals(INVOCATION_ID, request.invocationId());
        assertEquals(FLOW_ID, request.flowId());
        assertEquals(FLOW_VERSION_ID, request.flowVersionId());
        assertEquals(stepExecution.stepId(), request.stepId());
        assertEquals(1, request.attempt());
        assertEquals("FUNCTION", request.componentType());
        assertEquals(stepExecution.componentId(), request.componentId());
        assertEquals(stepExecution.componentVersionId(), request.componentVersionId());
        assertEquals("NODE", request.runtimeType());
        assertEquals("{\"path\":\"/orders\"}", request.input());
    }

    @Test
    void preservesComponentVersionPinning() {        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        InvocationStepExecution stepExecution = new InvocationStepExecution(
                EXECUTION_ID, INVOCATION_ID, FLOW_ID, FLOW_VERSION_ID, UUID.randomUUID(), 1,
                "FUNCTION", componentId, componentVersionId, "NODE", null,
                InvocationStepExecutionStatus.READY, 1, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null);
        Invocation invocation = invocation("{\"q\":1}");

        RuntimeExecutionRequest request = RuntimeExecutionRequest.fromStepExecution(stepExecution, invocation);

        assertEquals(componentId, request.componentId());
        assertEquals(componentVersionId, request.componentVersionId());
        assertTrue(request.flowId() != null);
    }

    @Test
    void nextStepRequestCarriesPreviousResultVerbatim() {
        InvocationStepExecution stepExecution = new InvocationStepExecution(
                UUID.randomUUID(), INVOCATION_ID, FLOW_ID, FLOW_VERSION_ID, UUID.randomUUID(), 2,
                "FUNCTION", UUID.randomUUID(), UUID.randomUUID(), "NODE", null,
                InvocationStepExecutionStatus.READY, 1, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null);

        RuntimeExecutionRequest request = RuntimeExecutionRequest.fromNextStepExecution(
                stepExecution, "{\"foo\":\"bar\"}");

        assertEquals(stepExecution.id(), request.executionId());
        assertEquals(1, request.attempt());
        assertEquals("{\"foo\":\"bar\"}", request.input());
    }

    private Invocation invocation(String inputPayload) {
        return new Invocation(
                INVOCATION_ID,
                FLOW_ID,
                "flw_orders_list",
                FLOW_VERSION_ID,
                com.funchole.backend.invocation.InvocationStatus.PENDING,
                inputPayload,
                "{}",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }
}
