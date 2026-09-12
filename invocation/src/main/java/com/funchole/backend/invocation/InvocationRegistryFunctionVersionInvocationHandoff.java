package com.funchole.backend.invocation;

/**
 * Default {@link FunctionVersionInvocationHandoff}: bridges a fully-resolved
 * direct FunctionVersion execution request straight onto
 * {@link InvocationRegistry#createDirectInvocation}, reusing the existing
 * durable Invocation creation, immutable execution snapshot, PENDING state,
 * and ready-event publication - the same Dispatcher/Runtime execution path
 * every other Invocation already goes through.
 */
public class InvocationRegistryFunctionVersionInvocationHandoff implements FunctionVersionInvocationHandoff {

    private final InvocationRegistry invocationRegistry;

    public InvocationRegistryFunctionVersionInvocationHandoff(InvocationRegistry invocationRegistry) {
        this.invocationRegistry = invocationRegistry;
    }

    @Override
    public DirectInvocationResult dispatch(DirectInvocationRequest request) {
        try {
            Invocation invocation = invocationRegistry.createDirectInvocation(request);
            return new DirectInvocationResult(
                    invocation.invocationId(),
                    invocation.functionVersionId(),
                    invocation.status().name()
            );
        } catch (RuntimeException exception) {
            throw new FunctionVersionInvocationDispatchException(
                    "Failed to dispatch direct invocation for function version " + request.functionVersionId(), exception);
        }
    }
}
