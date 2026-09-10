package com.funchole.backend.dispatcher;

import com.funchole.backend.invocation.InvocationFlowSnapshot;
import com.funchole.backend.invocation.InvocationSnapshot;
import com.funchole.backend.invocation.InvocationStepSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class InvocationSnapshotValidator {
    private static final Set<String> SUPPORTED_COMPONENT_TYPES = Set.of(
            "FUNCTION",
            "MIDDLEWARE",
            "RESPONSE",
            "MAPPING",
            "LOGICAL",
            "SUB_FLOW"
    );

    public InvocationValidationResult validate(InvocationSnapshot snapshot) {
        ArrayList<String> errors = new ArrayList<>();
        if (snapshot == null) {
            errors.add("Snapshot is required");
            return InvocationValidationResult.invalid(errors);
        }

        validateRoot(snapshot, errors);
        InvocationFlowSnapshot rootFlow = findRootFlow(snapshot);
        if (rootFlow == null) {
            errors.add("Root flow snapshot is required");
            return InvocationValidationResult.invalid(errors);
        }

        validateRuntime(rootFlow, errors);
        validateSteps(rootFlow, errors);
        return errors.isEmpty() ? InvocationValidationResult.success() : InvocationValidationResult.invalid(errors);
    }

    private void validateRuntime(InvocationFlowSnapshot rootFlow, ArrayList<String> errors) {
        if (rootFlow.runtime() == null || rootFlow.runtime().isBlank()) {
            errors.add("Root flow runtime is required");
        }
    }

    private void validateRoot(InvocationSnapshot snapshot, ArrayList<String> errors) {
        if (snapshot.rootFlowId() == null) {
            errors.add("Root flow id is required");
        }
        if (snapshot.rootFlowVersionId() == null) {
            errors.add("Root flow version id is required");
        }
        if (snapshot.flows() == null || snapshot.flows().isEmpty()) {
            errors.add("At least one flow snapshot is required");
        }
    }

    private InvocationFlowSnapshot findRootFlow(InvocationSnapshot snapshot) {
        if (snapshot.flows() == null) {
            return null;
        }

        for (InvocationFlowSnapshot flow : snapshot.flows()) {
            if (flow == null) {
                continue;
            }
            if (sameId(snapshot.rootFlowId(), flow.flowId())
                    && sameId(snapshot.rootFlowVersionId(), flow.flowVersionId())) {
                return flow;
            }
        }
        return null;
    }

    private void validateSteps(InvocationFlowSnapshot rootFlow, ArrayList<String> errors) {
        if (rootFlow.steps() == null || rootFlow.steps().isEmpty()) {
            errors.add("Root flow must contain at least one step");
            return;
        }

        Set<UUID> stepIds = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        int previousPosition = 0;
        for (int index = 0; index < rootFlow.steps().size(); index++) {
            InvocationStepSnapshot step = rootFlow.steps().get(index);
            if (step == null) {
                errors.add("Step at index " + index + " is required");
                continue;
            }

            if (step.stepId() == null) {
                errors.add("Step id is required at index " + index);
            } else if (!stepIds.add(step.stepId())) {
                errors.add("Duplicate step id: " + step.stepId());
            }

            if (step.position() <= 0) {
                errors.add("Step position must be positive for step " + readableStepId(step));
            } else {
                if (!positions.add(step.position())) {
                    errors.add("Duplicate step position: " + step.position());
                }
                if (step.position() <= previousPosition) {
                    errors.add("Step positions must be ordered ascending");
                }
                previousPosition = step.position();
            }

            validateComponentType(step, errors);
            validateComponentReference(step, errors);
        }
    }

    private void validateComponentType(InvocationStepSnapshot step, ArrayList<String> errors) {
        if (step.componentType() == null || step.componentType().isBlank()) {
            errors.add("Step component type is required for step " + readableStepId(step));
            return;
        }

        String normalizedType = step.componentType().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_COMPONENT_TYPES.contains(normalizedType)) {
            errors.add("Unsupported step component type '" + step.componentType() + "' for step " + readableStepId(step));
        }
    }

    private void validateComponentReference(InvocationStepSnapshot step, ArrayList<String> errors) {
        if (step.componentId() == null) {
            errors.add("Step component id is required for step " + readableStepId(step));
        }
        if (step.componentVersionId() == null) {
            errors.add("Step component version id is required for step " + readableStepId(step));
        }
    }

    private boolean sameId(UUID expected, UUID actual) {
        return expected != null && expected.equals(actual);
    }

    private String readableStepId(InvocationStepSnapshot step) {
        if (step.stepKey() != null && !step.stepKey().isBlank()) {
            return step.stepKey();
        }
        return step.stepId() == null ? "<unknown>" : step.stepId().toString();
    }
}
