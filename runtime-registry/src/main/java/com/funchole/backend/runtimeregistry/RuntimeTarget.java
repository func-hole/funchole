package com.funchole.backend.runtimeregistry;

/**
 * The runtime capacity selected and reserved for a Step Execution. This is a
 * selection result only; IPC/process/socket details are out of scope until
 * the runtime handoff milestone.
 */
public record RuntimeTarget(
        String runtimeInstanceId,
        String runtimeType
) {
}
