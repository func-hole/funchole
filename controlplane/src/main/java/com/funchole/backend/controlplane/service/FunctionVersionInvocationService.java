package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transport-neutral application service for directly invoking a READY
 * FunctionVersion without a Gateway, Flow, or FlowRoute.
 *
 * <pre>
 * READY FunctionVersion -&gt; readiness validation (this class)
 *                        -&gt; exactly-pinned invocation hand-off (port)
 *                        -&gt; existing invocation / dispatcher / runtime path
 * </pre>
 *
 * Responsibilities kept deliberately small:
 * <ul>
 *   <li>only READY FunctionVersions may be invoked (DRAFT/PUBLISHING/FAILED
 *       and missing versions are rejected) - nothing is auto-deployed,</li>
 *   <li>the supplied exact {@code functionVersionId} is authoritative and
 *       pinned into the immutable invocation snapshot; no active/latest
 *       version or Flow-version resolution happens,</li>
 *   <li>runtime selection is NOT decided here - the FunctionVersion's own
 *       runtime string is carried in the {@link FunctionVersionInvocationSpec}
 *       so the existing Dispatcher/Runtime Registry path selects the runtime
 *       as usual,</li>
 *   <li>dispatch failure propagates to the caller and follows the existing
 *       Invocation failure semantics (no silently stuck invocations).</li>
 * </ul>
 *
 * No synchronous waiting: callers receive the invocation id and initial
 * status immediately for later inspection.
 */
public class FunctionVersionInvocationService {

    private final FunctionVersionRepository functionVersionRepository;
    private final FunctionVersionInvocationHandoff invocationHandoff;

    public FunctionVersionInvocationService(
            FunctionVersionRepository functionVersionRepository,
            FunctionVersionInvocationHandoff invocationHandoff
    ) {
        this.functionVersionRepository = functionVersionRepository;
        this.invocationHandoff = invocationHandoff;
    }

    @Transactional(readOnly = true)
    public DirectInvocationResult invoke(FunctionVersionInvocationSpec request) {
        if (request == null || request.functionVersionId() == null) {
            throw new IllegalArgumentException("functionVersionId is required");
        }
        FunctionVersion functionVersion = functionVersionRepository.findById(request.functionVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Function version not found: " + request.functionVersionId()));
        if (functionVersion.getStatus() != FunctionVersionStatus.READY) {
            throw new IllegalStateException("Only READY function versions can be invoked directly, current status is "
                    + functionVersion.getStatus() + ": " + request.functionVersionId());
        }

        // The exact FunctionVersion is the pin: function identity, version id,
        // and runtime are copied from the durable FunctionVersion itself -
        // never resolved from active/latest versions or Flow data.
        FunctionVersionInvocationSpec pinnedSpec = new FunctionVersionInvocationSpec(
                functionVersion.getFunction().getId(),
                functionVersion.getFunction().getFunctionKey(),
                functionVersion.getId(),
                functionVersion.getRuntime(),
                request.inputPayload()
        );
        return invocationHandoff.dispatch(pinnedSpec);
    }
}
