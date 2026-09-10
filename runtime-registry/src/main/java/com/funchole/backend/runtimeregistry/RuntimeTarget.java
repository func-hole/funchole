package com.funchole.backend.runtimeregistry;

/**
 * The runtime capacity selected and reserved for a Step Execution.
 *
 * {@code socketPath} is the selected runtime's local IPC endpoint, carried
 * straight through from its {@link RuntimeInstance} registration. It is the
 * only transport detail this milestone exposes - process/channel lifecycle
 * still belongs entirely to the {@code RuntimeExecutionGateway} implementation.
 */
public record RuntimeTarget(
        String runtimeInstanceId,
        String runtimeType,
        String socketPath
) {
}
