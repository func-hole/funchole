package com.funchole.backend.dispatcher;

import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationFlowSnapshot;
import com.funchole.backend.invocation.InvocationSnapshot;
import com.funchole.backend.invocation.InvocationStepSnapshot;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides what should execute for an invocation, based only on the persisted
 * immutable Invocation snapshot.
 *
 * {@link #planInitialStep} resolves the first executable step of the root
 * flow. {@link #planNextStep} resolves the next ordered FUNCTION step after a
 * completed step position - again purely from the frozen snapshot, never from
 * current/latest Flow definitions.
 *
 * Snapshot ordering contract: the invocation registry persists steps ordered
 * by position and the snapshot validator enforces ascending positions, so the
 * first entry is the lowest position. The planner still selects by position
 * rather than trusting list order defensively.
 */
public class ExecutionPlanner {

    private static final Set<String> EXECUTABLE_COMPONENT_TYPES = Set.of("FUNCTION");

    /**
     * Component types {@link #planNextStep} will progress into. FUNCTION
     * steps run through the Runtime Registry/IPC; RESPONSE steps are executed
     * inline by the Dispatcher's orchestration layer (see
     * InvocationDispatcher) with no runtime involved. Other component types
     * are not supported yet and, like an absent next step, simply stop
     * progression.
     */
    private static final Set<String> PROGRESSABLE_COMPONENT_TYPES = Set.of("FUNCTION", "RESPONSE");

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

        String runtimeType = rootFlow.runtime() == null
                ? ""
                : rootFlow.runtime().trim().toUpperCase(Locale.ROOT);

        return new DispatchableStep(
                invocation.invocationId(),
                rootFlow.flowId(),
                rootFlow.flowVersionId(),
                candidate.stepId(),
                candidate.position(),
                candidate.stepKey(),
                componentType,
                candidate.componentId(),
                candidate.componentVersionId(),
                runtimeType
        );
    }

    /**
     * Resolves the next ordered progressable step after a completed step
     * position, from the immutable snapshot only.
     *
     * Returns empty when there is no further step at all, or when the next
     * ordered step's component type is not one this milestone progresses
     * into (see {@link #PROGRESSABLE_COMPONENT_TYPES}) - both are legitimate
     * "flow progression stops here" outcomes, not planner failures. The
     * caller (InvocationDispatcher) is responsible for routing a returned
     * RESPONSE step to inline orchestration instead of the Runtime Registry.
     */
    public Optional<DispatchableStep> planNextStep(Invocation invocation, InvocationSnapshot snapshot, int completedPosition) {
        InvocationFlowSnapshot rootFlow = findRootFlow(snapshot);
        if (rootFlow == null) {
            return Optional.empty();
        }
        InvocationStepSnapshot next = null;
        for (InvocationStepSnapshot step : rootFlow.steps() == null ? List.<InvocationStepSnapshot>of() : rootFlow.steps()) {
            if (step == null || step.position() <= completedPosition) {
                continue;
            }
            if (next == null || step.position() < next.position()) {
                next = step;
            }
        }
        if (next == null) {
            return Optional.empty();
        }
        String componentType = next.componentType() == null
                ? ""
                : next.componentType().trim().toUpperCase(Locale.ROOT);
        if (!PROGRESSABLE_COMPONENT_TYPES.contains(componentType)) {
            return Optional.empty();
        }
        if (next.componentId() == null || next.componentVersionId() == null) {
            return Optional.empty();
        }
        String runtimeType = rootFlow.runtime() == null
                ? ""
                : rootFlow.runtime().trim().toUpperCase(Locale.ROOT);
        return Optional.of(new DispatchableStep(
                invocation.invocationId(),
                rootFlow.flowId(),
                rootFlow.flowVersionId(),
                next.stepId(),
                next.position(),
                next.stepKey(),
                componentType,
                next.componentId(),
                next.componentVersionId(),
                runtimeType
        ));
    }

    /**
     * Returns the component type of the lowest-position step after
     * {@code completedPosition}, or empty if no further steps exist. The
     * caller uses this to distinguish "no next step" (legitimate stop) from
     * "next step exists but is unsupported" (Invocation failure).
     */
    public Optional<String> nextStepComponentType(InvocationSnapshot snapshot, int completedPosition) {
        InvocationFlowSnapshot rootFlow = findRootFlow(snapshot);
        if (rootFlow == null) {
            return Optional.empty();
        }
        InvocationStepSnapshot next = null;
        for (InvocationStepSnapshot step : rootFlow.steps() == null ? List.<InvocationStepSnapshot>of() : rootFlow.steps()) {
            if (step == null || step.position() <= completedPosition) {
                continue;
            }
            if (next == null || step.position() < next.position()) {
                next = step;
            }
        }
        if (next == null) {
            return Optional.empty();
        }
        String componentType = next.componentType() == null
                ? ""
                : next.componentType().trim().toUpperCase(Locale.ROOT);
        return Optional.of(componentType);
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
