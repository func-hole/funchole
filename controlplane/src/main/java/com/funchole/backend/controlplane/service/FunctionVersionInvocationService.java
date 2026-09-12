package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import com.funchole.backend.invocation.DirectInvocationRequest;
import com.funchole.backend.invocation.DirectInvocationResult;
import com.funchole.backend.invocation.FunctionVersionInvocationHandoff;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transport-neutral application service for directly invoking a READY
 * FunctionVersion without a Gateway, Flow, or FlowRoute.
 *
 * <pre>
 * caller command (functionVersionId + payload only)
 * -&gt; readiness validation (this class)
 * -&gt; durable FunctionVersion resolution -&gt; pinned request (identity/runtime from durable state)
 * -&gt; exactly-pinned invocation hand-off (the invocation module's own boundary contract)
 * -&gt; existing invocation / dispatcher / runtime path
 * </pre>
 *
 * Responsibilities kept deliberately small:
 * <ul>
 *   <li>only READY FunctionVersions may be invoked (DRAFT/PUBLISHING/FAILED
 *       and missing versions are rejected) - nothing is auto-deployed,</li>
 *   <li>the supplied exact {@code functionVersionId} is authoritative and
 *       pinned into the request handed across the boundary; no active/latest
 *       version or Flow-version resolution happens,</li>
 *   <li>runtime selection is NOT decided here - the FunctionVersion's own
 *       runtime string is carried in the {@link DirectInvocationRequest} so
 *       the existing Dispatcher/Runtime Registry path selects the runtime as
 *       usual,</li>
 *   <li>dispatch failure propagates to the caller and follows the existing
 *       Invocation failure semantics (no silently stuck invocations).</li>
 * </ul>
 *
 * <p>This class depends only on {@link FunctionVersionInvocationHandoff} -
 * the invocation module's own small, explicit boundary contract. It never
 * imports {@code InvocationRegistry}, {@code JdbcInvocationRegistry}, the
 * persisted {@code Invocation} record, the Dispatcher, or the Runtime
 * Registry.
 *
 * <p>No synchronous waiting: callers receive the invocation id and initial
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
    public DirectInvocationResult invoke(DirectFunctionInvocationCommand command) {
        if (command == null || command.functionVersionId() == null) {
            throw new IllegalArgumentException("functionVersionId is required");
        }
        FunctionVersion functionVersion = functionVersionRepository.findById(command.functionVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Function version not found: " + command.functionVersionId()));
        if (functionVersion.getStatus() != FunctionVersionStatus.READY) {
            throw new IllegalStateException("Only READY function versions can be invoked directly, current status is "
                    + functionVersion.getStatus() + ": " + command.functionVersionId());
        }

        // The exact FunctionVersion is the pin: function identity, version id,
        // and runtime are copied from the durable FunctionVersion itself -
        // never resolved from active/latest versions or Flow data, and never
        // taken from caller input.
        DirectInvocationRequest request = new DirectInvocationRequest(
                functionVersion.getFunction().getId(),
                functionVersion.getFunction().getFunctionKey(),
                functionVersion.getId(),
                functionVersion.getRuntime(),
                command.inputPayload()
        );
        return invocationHandoff.dispatch(request);
    }
}
