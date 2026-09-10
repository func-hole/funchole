package com.funchole.backend.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationFlowSnapshot;
import com.funchole.backend.invocation.InvocationSnapshot;
import com.funchole.backend.invocation.InvocationStatus;
import com.funchole.backend.invocation.InvocationStepSnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionPlannerTest {

    private static final UUID INVOCATION_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID FLOW_ID = UUID.fromString("10000000-0000-0000-0000-000000000101");
    private static final UUID FLOW_VERSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000101");

    private final ExecutionPlanner planner = new ExecutionPlanner();

    @Test
    void plansLowestPositionFunctionStepWithFullReferences() {
        InvocationStepSnapshot validateOrders = step(
                "validate-orders-request", "FUNCTION", 1,
                "88888888-8888-8888-8888-888888888861", "99999999-9999-9999-9999-999999999861");
        InvocationStepSnapshot fetchOrders = step(
                "fetch-orders", "FUNCTION", 2,
                "88888888-8888-8888-8888-888888888862", "99999999-9999-9999-9999-999999999862");
        InvocationStepSnapshot buildResponse = step(
                "build-orders-response", "FUNCTION", 3,
                "88888888-8888-8888-8888-888888888863", "99999999-9999-9999-9999-999999999863");
        InvocationSnapshot snapshot = snapshot(List.of(validateOrders, fetchOrders, buildResponse));

        DispatchableStep planned = planner.planInitialStep(invocation(), snapshot);

        assertEquals(INVOCATION_ID, planned.invocationId());
        assertEquals(FLOW_ID, planned.flowId());
        assertEquals(FLOW_VERSION_ID, planned.flowVersionId());
        assertEquals(validateOrders.stepId(), planned.stepId());
        assertEquals(1, planned.position());
        assertEquals("validate-orders-request", planned.stepKey());
        assertEquals("FUNCTION", planned.componentType());
        assertEquals(validateOrders.componentId(), planned.componentId());
        assertEquals(validateOrders.componentVersionId(), planned.componentVersionId());
        assertEquals("NODE", planned.runtimeType());
    }

    @Test
    void normalizesRuntimeTypeFromRootFlowSnapshot() {
        InvocationStepSnapshot validateOrders = step(
                "validate-orders-request", "FUNCTION", 1,
                "88888888-8888-8888-8888-888888888861", "99999999-9999-9999-9999-999999999861");
        InvocationFlowSnapshot rootFlow = new InvocationFlowSnapshot(
                FLOW_ID, "flw_orders_list", FLOW_VERSION_ID, 1, "ADOPTED", "node", null, List.of(validateOrders));
        InvocationSnapshot snapshot = new InvocationSnapshot(FLOW_ID, "flw_orders_list", FLOW_VERSION_ID, List.of(rootFlow));

        DispatchableStep planned = planner.planInitialStep(invocation(), snapshot);

        assertEquals("NODE", planned.runtimeType());
    }

    @Test
    void selectsByPositionNotListArrangement() {
        InvocationStepSnapshot positionOne = step(
                "validate-orders-request", "FUNCTION", 1,
                "88888888-8888-8888-8888-888888888861", "99999999-9999-9999-9999-999999999861");
        InvocationStepSnapshot positionTwo = step(
                "fetch-orders", "FUNCTION", 2,
                "88888888-8888-8888-8888-888888888862", "99999999-9999-9999-9999-999999999862");

        DispatchableStep planned = planner.planInitialStep(
                invocation(), snapshot(List.of(positionTwo, positionOne)));

        assertEquals(positionOne.stepId(), planned.stepId());
        assertEquals(1, planned.position());
    }

    @Test
    void lowerPositionWinsRegardlessOfInsertionOrder() {
        InvocationStepSnapshot positionThree = step(
                "build-orders-response", "FUNCTION", 3,
                "88888888-8888-8888-8888-888888888863", "99999999-9999-9999-9999-999999999863");
        InvocationStepSnapshot positionTwo = step(
                "fetch-orders", "FUNCTION", 2,
                "88888888-8888-8888-8888-888888888862", "99999999-9999-9999-9999-999999999862");
        InvocationStepSnapshot positionOne = step(
                "validate-orders-request", "FUNCTION", 1,
                "88888888-8888-8888-8888-888888888861", "99999999-9999-9999-9999-999999999861");

        DispatchableStep planned = planner.planInitialStep(
                invocation(), snapshot(List.of(positionThree, positionTwo, positionOne)));

        assertEquals(positionOne.stepId(), planned.stepId());
        assertEquals(1, planned.position());
    }

    @Test
    void failsWhenRootFlowHasNoSteps() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> planner.planInitialStep(invocation(), snapshot(List.of())));

        assertTrue(exception.getMessage().contains("no steps"));
    }

    @Test
    void failsClearlyForUnsupportedExecutableStepType() {
        InvocationStepSnapshot middlewareStep = step(
                "log-request", "MIDDLEWARE", 1,
                "88888888-8888-8888-8888-888888888871", "99999999-9999-9999-9999-999999999871");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> planner.planInitialStep(invocation(), snapshot(List.of(middlewareStep))));

        assertTrue(exception.getMessage().contains("MIDDLEWARE"));
        assertTrue(exception.getMessage().contains("not dispatchable"));
    }

    @Test
    void failsWhenComponentReferenceMissing() {
        InvocationStepSnapshot incompleteStep = new InvocationStepSnapshot(
                UUID.fromString("a0000000-0000-0000-0000-000000000001"),
                "validate-orders-request",
                "FUNCTION",
                1,
                UUID.fromString("88888888-8888-8888-8888-888888888861"),
                null,
                null
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> planner.planInitialStep(invocation(), snapshot(List.of(incompleteStep))));

        assertTrue(exception.getMessage().contains("pinned component/version"));
    }

    private Invocation invocation() {
        return new Invocation(
                INVOCATION_ID,
                FLOW_ID,
                "flw_orders_list",
                FLOW_VERSION_ID,
                InvocationStatus.PENDING,
                "{}",
                "{}",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private InvocationSnapshot snapshot(List<InvocationStepSnapshot> steps) {
        InvocationFlowSnapshot rootFlow = new InvocationFlowSnapshot(
                FLOW_ID,
                "flw_orders_list",
                FLOW_VERSION_ID,
                1,
                "ADOPTED",
                "NODE",
                null,
                steps
        );
        return new InvocationSnapshot(FLOW_ID, "flw_orders_list", FLOW_VERSION_ID, List.of(rootFlow));
    }

    private InvocationStepSnapshot step(
            String stepKey,
            String componentType,
            int position,
            String componentId,
            String componentVersionId
    ) {
        return new InvocationStepSnapshot(
                UUID.randomUUID(),
                stepKey,
                componentType,
                position,
                UUID.fromString(componentId),
                UUID.fromString(componentVersionId),
                null
        );
    }
}
