package com.funchole.backend.controlplane.functionbuild.runtime.node;

import java.util.List;
import java.util.UUID;

/**
 * Structured, transport-neutral diagnostics for a failed Node build stage
 * (currently: dependency installation). Plain {@link RuntimeException}
 * subtype - no HTTP/MCP/CLI response type - so every future adapter can
 * catch it and render its own representation from {@link #stage()},
 * {@link #command()}, {@link #exitCode()}, {@link #stdout()},
 * {@link #stderr()}, and {@link #timedOut()}.
 */
public final class NodeBuildException extends RuntimeException {

    private final UUID functionVersionId;
    private final String stage;
    private final List<String> command;
    private final Integer exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean timedOut;

    public NodeBuildException(
            UUID functionVersionId,
            String stage,
            List<String> command,
            Integer exitCode,
            String stdout,
            String stderr,
            boolean timedOut
    ) {
        super(buildMessage(functionVersionId, stage, command, exitCode, timedOut));
        this.functionVersionId = functionVersionId;
        this.stage = stage;
        this.command = List.copyOf(command);
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.timedOut = timedOut;
    }

    public UUID functionVersionId() {
        return functionVersionId;
    }

    public String stage() {
        return stage;
    }

    public List<String> command() {
        return command;
    }

    public Integer exitCode() {
        return exitCode;
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    public boolean timedOut() {
        return timedOut;
    }

    private static String buildMessage(
            UUID functionVersionId, String stage, List<String> command, Integer exitCode, boolean timedOut) {
        String commandText = String.join(" ", command);
        if (timedOut) {
            return "Node build stage '" + stage + "' timed out running '" + commandText
                    + "' for function version: " + functionVersionId;
        }
        return "Node build stage '" + stage + "' failed (exit code " + exitCode + ") running '" + commandText
                + "' for function version: " + functionVersionId;
    }
}
