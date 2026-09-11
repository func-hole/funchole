package com.funchole.backend.controlplane.constant;

/**
 * Only the component types the Dispatcher's ExecutionPlanner can actually progress today.
 * Widen this as the Dispatcher gains support for more component types.
 */
public enum FlowStepComponentType {
    FUNCTION,
    RESPONSE
}
