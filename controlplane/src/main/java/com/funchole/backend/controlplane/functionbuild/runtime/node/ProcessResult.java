package com.funchole.backend.controlplane.functionbuild.runtime.node;

/**
 * Outcome of one {@link ProcessExecutor#execute} call. {@code exitCode} is
 * {@code null} exactly when {@code timedOut} is {@code true} - a killed
 * process never produces one.
 */
public record ProcessResult(Integer exitCode, String stdout, String stderr, boolean timedOut) {

    public boolean succeeded() {
        return !timedOut && exitCode != null && exitCode == 0;
    }
}
