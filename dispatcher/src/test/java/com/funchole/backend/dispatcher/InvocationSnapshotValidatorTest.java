package com.funchole.backend.dispatcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.funchole.backend.invocation.InvocationFlowSnapshot;
import com.funchole.backend.invocation.InvocationSnapshot;
import com.funchole.backend.invocation.InvocationStepSnapshot;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvocationSnapshotValidatorTest {

    private static final UUID FLOW_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID FLOW_VERSION_ID = UUID.fromString("66666666-6666-6666-6666-666666666661");
    private static final UUID STEP_ID = UUID.fromString("77777777-7777-7777-7777-777777777761");
    private static final UUID COMPONENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888861");
    private static final UUID COMPONENT_VERSION_ID = UUID.fromString("99999999-9999-9999-9999-999999999861");

    private final InvocationSnapshotValidator validator = new InvocationSnapshotValidator();

    @Test
    void validatesExecutableShapedSnapshot() {
        InvocationValidationResult result = validator.validate(snapshot(List.of(
                step(STEP_ID, "validate-orders-request", "FUNCTION", 1, COMPONENT_ID, COMPONENT_VERSION_ID),
                step(
                        UUID.fromString("77777777-7777-7777-7777-777777777762"),
                        "fetch-orders",
                        "FUNCTION",
                        2,
                        UUID.fromString("88888888-8888-8888-8888-888888888862"),
                        UUID.fromString("99999999-9999-9999-9999-999999999862")
                ),
                step(
                        UUID.fromString("77777777-7777-7777-7777-777777777763"),
                        "build-orders-response",
                        "FUNCTION",
                        3,
                        UUID.fromString("88888888-8888-8888-8888-888888888863"),
                        UUID.fromString("99999999-9999-9999-9999-999999999863")
                )
        )));

        assertTrue(result.valid());
    }

    @Test
    void rejectsSnapshotWithoutSteps() {
        InvocationValidationResult result = validator.validate(snapshot(List.of()));

        assertFalse(result.valid());
        assertTrue(result.errors().contains("Root flow must contain at least one step"));
    }

    @Test
    void rejectsMissingStepId() {
        InvocationValidationResult result = validator.validate(snapshot(List.of(
                step(null, "validate-orders-request", "FUNCTION", 1, COMPONENT_ID, COMPONENT_VERSION_ID)
        )));

        assertFalse(result.valid());
    }

    @Test
    void rejectsInvalidPosition() {
        InvocationValidationResult result = validator.validate(snapshot(List.of(
                step(STEP_ID, "validate-orders-request", "FUNCTION", 0, COMPONENT_ID, COMPONENT_VERSION_ID)
        )));

        assertFalse(result.valid());
    }

    @Test
    void rejectsDuplicateStepPosition() {
        InvocationValidationResult result = validator.validate(snapshot(List.of(
                step(STEP_ID, "validate-orders-request", "FUNCTION", 1, COMPONENT_ID, COMPONENT_VERSION_ID),
                step(
                        UUID.fromString("77777777-7777-7777-7777-777777777762"),
                        "fetch-orders",
                        "FUNCTION",
                        1,
                        UUID.fromString("88888888-8888-8888-8888-888888888862"),
                        UUID.fromString("99999999-9999-9999-9999-999999999862")
                )
        )));

        assertFalse(result.valid());
    }

    @Test
    void rejectsMissingRequiredComponentReference() {
        InvocationValidationResult result = validator.validate(snapshot(List.of(
                step(STEP_ID, "validate-orders-request", "FUNCTION", 1, null, COMPONENT_VERSION_ID)
        )));

        assertFalse(result.valid());
    }

    @Test
    void rejectsUnsupportedComponentType() {
        InvocationValidationResult result = validator.validate(snapshot(List.of(
                step(STEP_ID, "validate-orders-request", "UNKNOWN", 1, COMPONENT_ID, COMPONENT_VERSION_ID)
        )));

        assertFalse(result.valid());
    }

    @Test
    void rejectsMissingRootFlowRuntime() {
        InvocationSnapshot missingRuntimeSnapshot = new InvocationSnapshot(
                FLOW_ID,
                "flw_orders_list",
                FLOW_VERSION_ID,
                List.of(new InvocationFlowSnapshot(
                        FLOW_ID,
                        "flw_orders_list",
                        FLOW_VERSION_ID,
                        1,
                        "ADOPTED",
                        null,
                        null,
                        List.of(step(STEP_ID, "validate-orders-request", "FUNCTION", 1, COMPONENT_ID, COMPONENT_VERSION_ID))
                ))
        );

        InvocationValidationResult result = validator.validate(missingRuntimeSnapshot);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("Root flow runtime is required"));
    }

    private InvocationSnapshot snapshot(List<InvocationStepSnapshot> steps) {
        return new InvocationSnapshot(
                FLOW_ID,
                "flw_orders_list",
                FLOW_VERSION_ID,
                List.of(new InvocationFlowSnapshot(
                        FLOW_ID,
                        "flw_orders_list",
                        FLOW_VERSION_ID,
                        1,
                        "ADOPTED",
                        "NODE",
                        null,
                        steps
                ))
        );
    }

    private InvocationStepSnapshot step(
            UUID stepId,
            String stepKey,
            String componentType,
            int position,
            UUID componentId,
            UUID componentVersionId
    ) {
        return new InvocationStepSnapshot(
                stepId,
                stepKey,
                componentType,
                position,
                componentId,
                componentVersionId,
                "{\"name\":\"" + stepKey + "\"}"
        );
    }
}
