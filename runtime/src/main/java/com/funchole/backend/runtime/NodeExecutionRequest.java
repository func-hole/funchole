package com.funchole.backend.runtime;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Java Runtime Worker -> Node Executor handoff. Everything the artifact
 * needs is already here; the Node process never queries the FuncHole
 * database.
 */
public record NodeExecutionRequest(
        UUID executionId,
        UUID componentId,
        UUID componentVersionId,
        Path artifactPath,
        String input
) {
}
