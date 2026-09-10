package com.funchole.backend.dispatcher;

import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationFlowSnapshot;
import com.funchole.backend.invocation.InvocationSnapshot;
import com.funchole.backend.invocation.InvocationStepSnapshot;
import java.util.Locale;
import java.util.Set;

/**
 * Decides what should execute next for an invocation, based only on the
 * persisted immutable Invocation snapshot.
 *
 * For this milestone there is no execution-state tracking, so planning means
 * resolving the first dispatchable step of the root flow.
 *
 * Snapshot ordering contract: the invocation registry persists steps ordered
 * by position and the snapshot validator enforces ascending positions, so the
 * first entry is the lowest position. The planner still selects by position
 * rather than trusting list order defensively.
 */
public class ExecutionPlanner {

    private static final Set<String> EXECUTABLE_COMPONENT_TYPES = Set.of("FUNCTION");

    public DispatchableStep planInitialStep(Invocation invocation, InvocationSnapshot snapshot) {
        InvocationFlowSnapshot rootFlow = findRootFlow(snapshot);
        if (rootFlow == null) {
            throw new IllegalStateException(
                    "Planning failed: root flow not found in snapshot for invocation " + invocation.invocationId());
        }
        if (rootFlow.steps() == null || rootFlow.steps().isEmpty()) {
            throw new IllegalStateException(
                    "Planning failed: root flow contains no steps for invocation " + invocation.invocationId());
        }

        InvocationStepSnapshot candidate = null;
        for (InvocationStepSnapshot step : rootFlow.steps()) {
            if (step == null) {
                continue;
            }
            if (candidate == null || step.position() < candidate.position()) {
                candidate = step;
            }
        }
        if (candidate == null) {
            throw new IllegalStateException(
                    "Planning failed: root flow contains no resolvable steps for invocation " + invocation.invocationId());
        }

        String componentType = candidate.componentType() == null
                ? ""
                : candidate.componentType().trim().toUpperCase(Locale.ROOT);
        if (!EXECUTABLE_COMPONENT_TYPES.contains(componentType)) {
            throw new IllegalStateException("Planning failed: step " + readableStep(candidate)
                    + " has component type '" + candidate.componentType() + "' which is not dispatchable yet");
        }
        if (candidate.componentId() == null || candidate.componentVersionId() == null) {
            throw new IllegalStateException("Planning failed: step " + readableStep(candidate)
                    + " is missing its pinned component/version reference");
        }

        return new DispatchableStep(
                invocation.invocationId(),
                rootFlow.flowId(),
                rootFlow.flowVersionId(),
                candidate.stepId(),
                candidate.position(),
                candidate.stepKey(),
                componentType,
                candidate.componentId(),
                candidate.componentVersionId()
        );
    }

    private InvocationFlowSnapshot findRootFlow(InvocationSnapshot snapshot) {
        if (snapshot.flows() == null) {
            return null;
        }
        for (InvocationFlowSnapshot flow : snapshot.flows()) {
            if (flow == null) {
                continue;
            }
            if (snapshot.rootFlowId() != null
                    && snapshot.rootFlowId().equals(flow.flowId())
                    && snapshot.rootFlowVersionId() != null
                    && snapshot.rootFlowVersionId().equals(flow.flowVersionId())) {
                return flow;
            }
        }
        return null;
    }

    private String readableStep(InvocationStepSnapshot step) {
        if (step.stepKey() != null && !step.stepKey().isBlank()) {
            return step.stepKey();
        }
        return step.stepId() == null ? "<unknown>" : step.stepId().toString();
    }
}
