package com.funchole.backend.controlplane.functionbuild;

import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.entity.SourceBundle;
import com.funchole.backend.controlplane.entity.SourceFile;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.controlplane.service.FunctionVersionSourceService;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transport-neutral, runtime-neutral application service that materializes
 * an exact FunctionVersion's already-submitted source into an isolated
 * temporary {@link BuildWorkspace}. What happens to that workspace next
 * (dependency install, bundling, packaging) is entirely up to whichever
 * {@link RuntimeBuilder} the caller resolves for the version's runtime type -
 * this service knows nothing about Node, Python, Go, or any other runtime.
 * Depends only on a repository and {@link FunctionVersionSourceService} - no
 * HTTP, MCP, CLI, or UI type ever appears in its signatures.
 *
 * <p>Building is only allowed while the FunctionVersion is PUBLISHING: that
 * is exactly the window in which {@link FunctionVersionSourceService}
 * already refuses further source submissions, so the source this service
 * reads is guaranteed frozen for the duration of the build without any
 * extra locking here.
 */
@Service
public class BuildWorkspaceService {

    private static final String WORKSPACE_DIRECTORY_PREFIX = "funchole-build-";

    private final FunctionVersionRepository functionVersionRepository;
    private final FunctionVersionSourceService sourceService;

    public BuildWorkspaceService(
            FunctionVersionRepository functionVersionRepository,
            FunctionVersionSourceService sourceService
    ) {
        this.functionVersionRepository = functionVersionRepository;
        this.sourceService = sourceService;
    }

    @Transactional(readOnly = true)
    public BuildWorkspace prepareWorkspace(UUID functionVersionId) {
        if (functionVersionId == null) {
            throw new IllegalArgumentException("functionVersionId is required");
        }
        requirePublishing(functionVersionId);
        SourceBundle sourceBundle = sourceService.findSource(functionVersionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No source submitted for function version: " + functionVersionId));
        return materialize(functionVersionId, sourceBundle);
    }

    private void requirePublishing(UUID functionVersionId) {
        FunctionVersion functionVersion = functionVersionRepository.findById(functionVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Function version not found: " + functionVersionId));
        if (functionVersion.getStatus() != FunctionVersionStatus.PUBLISHING) {
            throw new IllegalStateException(
                    "Function version must be PUBLISHING to build source, current status is "
                            + functionVersion.getStatus() + ": " + functionVersionId);
        }
    }

    /**
     * Materializes an already-loaded source bundle into a fresh workspace.
     * Package-private: {@link #prepareWorkspace} is the transport-neutral
     * entry point that loads the bundle through {@link FunctionVersionSourceService};
     * this method exists separately so the workspace-escape and
     * entrypoint-existence guards below stay exercisable directly, since a
     * bundle that violates either can never actually reach this point
     * through the real submission path (it is already rejected at
     * {@code FunctionVersionSourceService.submitSource} time).
     */
    BuildWorkspace materialize(UUID functionVersionId, SourceBundle sourceBundle) {
        Path workspaceRoot = createWorkspaceDirectory(functionVersionId);
        try {
            for (SourceFile file : sourceBundle.files()) {
                writeIntoWorkspace(workspaceRoot, file);
            }
            Path entrypointPath = resolveWithinWorkspace(workspaceRoot, sourceBundle.entrypoint());
            if (!Files.isRegularFile(entrypointPath)) {
                throw new IllegalStateException(
                        "Configured entrypoint was not found in the materialized workspace: " + sourceBundle.entrypoint());
            }
            return new BuildWorkspace(
                    functionVersionId,
                    workspaceRoot,
                    sourceBundle.entrypoint(),
                    sourceBundle.runtimeType(),
                    sourceBundle.runtimeVersion()
            );
        } catch (RuntimeException exception) {
            deleteRecursively(workspaceRoot);
            throw exception;
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                    // Partial-workspace cleanup on a failed build is best-effort.
                }
            });
        } catch (IOException ignored) {
            // Partial-workspace cleanup on a failed build is best-effort.
        }
    }

    private Path createWorkspaceDirectory(UUID functionVersionId) {
        try {
            return Files.createTempDirectory(WORKSPACE_DIRECTORY_PREFIX + functionVersionId + "-");
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to create build workspace for function version: " + functionVersionId, exception);
        }
    }

    private void writeIntoWorkspace(Path workspaceRoot, SourceFile file) {
        Path target = resolveWithinWorkspace(workspaceRoot, file.relativePath());
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content());
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to materialize source file: " + file.relativePath(), exception);
        }
    }

    private Path resolveWithinWorkspace(Path workspaceRoot, String relativePath) {
        Path target = workspaceRoot.resolve(relativePath).normalize();
        if (!target.equals(workspaceRoot) && !target.startsWith(workspaceRoot)) {
            throw new IllegalStateException("Source file escapes the build workspace: " + relativePath);
        }
        return target;
    }
}
