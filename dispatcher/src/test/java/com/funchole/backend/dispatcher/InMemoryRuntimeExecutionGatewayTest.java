package com.funchole.backend.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.funchole.backend.runtimeregistry.RuntimeTarget;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryRuntimeExecutionGatewayTest {

    private final InMemoryRuntimeExecutionGateway gateway = new InMemoryRuntimeExecutionGateway();

    @Test
    void acceptsCompatibleNodeTargetAndRecordsRequest() {
        RuntimeExecutionRequest request = validRequest();

        RuntimeExecutionAcceptance acceptance = gateway.handoff(target("NODE"), request);

        assertTrue(acceptance.accepted());
        assertEquals(request.executionId(), acceptance.executionId());
        assertEquals(1, gateway.acceptedCount());
        assertEquals(request, gateway.acceptedRequest(request.executionId()).orElseThrow());
    }

    @Test
    void rejectsIncompatibleRuntimeTarget() {
        RuntimeExecutionRequest request = validRequest("NODE");

        RuntimeExecutionAcceptance acceptance = gateway.handoff(target("PYTHON"), request);

        assertTrue(!acceptance.accepted());
        assertTrue(acceptance.rejectionReason().contains("not compatible"));
        assertEquals(0, gateway.acceptedCount());
    }

    @Test
    void rejectsMissingExecutionId() {
        RuntimeExecutionRequest request = new RuntimeExecutionRequest(
                null, UUID.randomUUID(), null, null, UUID.randomUUID(), 1,
                "FUNCTION", UUID.randomUUID(), UUID.randomUUID(), "NODE", "{}");

        RuntimeExecutionAcceptance acceptance = gateway.handoff(target("NODE"), request);

        assertTrue(!acceptance.accepted());
        assertTrue(acceptance.rejectionReason().contains("executionId is required"));
    }

    @Test
    void rejectsMissingComponentVersionId() {
        RuntimeExecutionRequest request = new RuntimeExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), null, null, UUID.randomUUID(), 1,
                "FUNCTION", UUID.randomUUID(), null, "NODE", "{}");

        RuntimeExecutionAcceptance acceptance = gateway.handoff(target("NODE"), request);

        assertTrue(!acceptance.accepted());
        assertTrue(acceptance.rejectionReason().contains("componentVersionId is required"));
    }

    @Test
    void rejectsNonPositiveAttempt() {
        RuntimeExecutionRequest request = new RuntimeExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), null, null, UUID.randomUUID(), 0,
                "FUNCTION", UUID.randomUUID(), UUID.randomUUID(), "NODE", "{}");

        RuntimeExecutionAcceptance acceptance = gateway.handoff(target("NODE"), request);

        assertTrue(!acceptance.accepted());
        assertTrue(acceptance.rejectionReason().contains("attempt must be positive"));
    }

    @Test
    void rejectsMissingTarget() {
        RuntimeExecutionAcceptance acceptance = gateway.handoff(null, validRequest("NODE"));

        assertTrue(!acceptance.accepted());
        assertTrue(acceptance.rejectionReason().contains("Runtime target is required"));
    }

    @Test
    void isIdempotentForSameExecutionId() {
        RuntimeExecutionRequest first = validRequest("NODE");
        RuntimeExecutionRequest second = new RuntimeExecutionRequest(
                first.executionId(), UUID.randomUUID(), null, null, UUID.randomUUID(), 1,
                "FUNCTION", UUID.randomUUID(), UUID.randomUUID(), "NODE", "{}");

        RuntimeExecutionAcceptance firstAcceptance = gateway.handoff(target("NODE"), first);
        RuntimeExecutionAcceptance secondAcceptance = gateway.handoff(target("NODE"), second);

        assertTrue(firstAcceptance.accepted());
        assertTrue(secondAcceptance.accepted());
        assertEquals(1, gateway.acceptedCount());
        assertEquals(first, gateway.acceptedRequest(first.executionId()).orElseThrow());
    }
    private RuntimeTarget target(String runtimeType) {
        return new RuntimeTarget("runtime-" + runtimeType.toLowerCase() + "-dev-1", runtimeType, "/tmp/test.sock");
    }

    private RuntimeExecutionRequest validRequest() {
        return validRequest("NODE");
    }

    private RuntimeExecutionRequest validRequest(String runtimeType) {
        return new RuntimeExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.fromString("10000000-0000-0000-0000-000000000101"),
                UUID.fromString("20000000-0000-0000-0000-000000000101"),
                UUID.randomUUID(),
                1,
                "FUNCTION",
                UUID.fromString("88888888-8888-8888-8888-888888888861"),
                UUID.fromString("99999999-9999-9999-9999-999999999861"),
                runtimeType,
                "{\"path\":\"/orders\"}"
        );
    }
}
