package com.funchole.backend.controlplane.functionbuild.runtime.node;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Small, testable boundary around running an external build command (e.g.
 * {@code npm ci}) so {@link NodeRuntimeBuilder} never calls
 * {@link ProcessBuilder} directly. Tests substitute a fake implementation
 * instead of spawning real processes or hitting the npm registry.
 */
public interface ProcessExecutor {

    /**
     * Runs {@code command} in {@code workingDirectory}, capturing stdout and
     * stderr. If the process does not finish within {@code timeout}, it is
     * killed and the result is returned with {@code timedOut} set.
     */
    ProcessResult execute(List<String> command, Path workingDirectory, Duration timeout);
}
