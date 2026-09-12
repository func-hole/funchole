package com.funchole.backend.controlplane.functionbuild.runtime.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.controlplane.functionbuild.BuildWorkspace;
import com.funchole.backend.controlplane.functionbuild.PreparedArtifact;
import com.funchole.backend.controlplane.functionbuild.process.ProcessExecutor;
import com.funchole.backend.controlplane.functionbuild.process.ProcessResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plain unit tests: {@link NodeRuntimeBuilder} depends only on
 * {@link ProcessExecutor}, so a hand-written fake is enough - no Spring
 * context, database, real npm, or network access needed.
 */
class NodeRuntimeBuilderTests {

    @TempDir
    Path tempDir;

    private final FakeProcessExecutor processExecutor = new FakeProcessExecutor();
    private final NodeRuntimeBuilder builder = new NodeRuntimeBuilder(processExecutor);

    @Test
    void dependencyFreeSourceBuildsWithoutInvokingNpm() {
        BuildWorkspace workspace = workspaceWithFiles("index.js", "console.log('hi')");

        try (PreparedArtifact artifact = builder.build(workspace)) {
            assertThat(processExecutor.invocationCount()).isZero();
            assertThat(readString(artifact.artifactDirectory().resolve("index.js"))).isEqualTo("console.log('hi')");
        }
    }

    @Test
    void packageJsonInvokesDependencyInstallation() {
        BuildWorkspace workspace = workspaceWithFiles(
                "index.js", "console.log('hi')",
                "package.json", "{}");
        processExecutor.nextResult(new ProcessResult(0, "installed", "", false));

        try (PreparedArtifact artifact = builder.build(workspace)) {
            assertThat(processExecutor.invocationCount()).isEqualTo(1);
            assertThat(processExecutor.lastCommand()).isEqualTo(List.of("npm", "install"));
            assertThat(artifact.entrypoint()).isEqualTo("index.js");
        }
    }

    @Test
    void packageLockJsonSelectsNpmCi() {
        BuildWorkspace workspace = workspaceWithFiles(
                "index.js", "console.log('hi')",
                "package.json", "{}",
                "package-lock.json", "{}");
        processExecutor.nextResult(new ProcessResult(0, "installed", "", false));

        try (PreparedArtifact artifact = builder.build(workspace)) {
            assertThat(processExecutor.lastCommand()).isEqualTo(List.of("npm", "ci"));
        }
    }

    @Test
    void npmFailureExposesStructuredDiagnostics() {
        UUID functionVersionId = UUID.randomUUID();
        BuildWorkspace workspace = workspaceWithFiles(functionVersionId,
                "index.js", "console.log('hi')",
                "package.json", "{}");
        processExecutor.nextResult(new ProcessResult(1, "some stdout", "some stderr", false));

        assertThatThrownBy(() -> builder.build(workspace))
                .isInstanceOf(NodeBuildException.class)
                .satisfies(exception -> {
                    NodeBuildException buildException = (NodeBuildException) exception;
                    assertThat(buildException.functionVersionId()).isEqualTo(functionVersionId);
                    assertThat(buildException.stage()).isEqualTo(NodeRuntimeBuilder.STAGE_DEPENDENCY_INSTALL);
                    assertThat(buildException.command()).isEqualTo(List.of("npm", "install"));
                    assertThat(buildException.exitCode()).isEqualTo(1);
                    assertThat(buildException.stdout()).isEqualTo("some stdout");
                    assertThat(buildException.stderr()).isEqualTo("some stderr");
                    assertThat(buildException.timedOut()).isFalse();
                });
    }

    @Test
    void timeoutIsSurfacedClearly() {
        BuildWorkspace workspace = workspaceWithFiles(
                "index.js", "console.log('hi')",
                "package.json", "{}");
        processExecutor.nextResult(new ProcessResult(null, "partial stdout", "partial stderr", true));

        assertThatThrownBy(() -> builder.build(workspace))
                .isInstanceOf(NodeBuildException.class)
                .satisfies(exception -> {
                    NodeBuildException buildException = (NodeBuildException) exception;
                    assertThat(buildException.timedOut()).isTrue();
                    assertThat(buildException.exitCode()).isNull();
                    assertThat(buildException.stdout()).isEqualTo("partial stdout");
                    assertThat(buildException.stderr()).isEqualTo("partial stderr");
                });
    }

    @Test
    void nestedFilesRemainIntact() {
        BuildWorkspace workspace = workspaceWithFiles(
                "src/index.js", "entry",
                "lib/client.js", "client");

        try (PreparedArtifact artifact = builder.build(workspace)) {
            assertThat(readString(artifact.artifactDirectory().resolve("src/index.js"))).isEqualTo("entry");
            assertThat(readString(artifact.artifactDirectory().resolve("lib/client.js"))).isEqualTo("client");
        }
    }

    @Test
    void entrypointRemainsValid() {
        BuildWorkspace workspace = workspaceWithFiles(
                "src/index.js", "entry",
                "package.json", "{}");
        processExecutor.nextResult(new ProcessResult(0, "", "", false));

        try (PreparedArtifact artifact = builder.build(workspace)) {
            assertThat(Files.isRegularFile(artifact.artifactDirectory().resolve(artifact.entrypoint()))).isTrue();
        }
    }

    @Test
    void buildWorkspaceIsNotMutated() {
        BuildWorkspace workspace = workspaceWithFiles(
                "index.js", "console.log('hi')",
                "package.json", "{}");
        processExecutor.nextResult(new ProcessResult(0, "", "", false));

        try (PreparedArtifact artifact = builder.build(workspace)) {
            assertThat(readString(workspace.root().resolve("index.js"))).isEqualTo("console.log('hi')");
            assertThat(readString(workspace.root().resolve("package.json"))).isEqualTo("{}");
            // No node_modules or lockfile written back into the original workspace.
            assertThat(Files.exists(workspace.root().resolve("node_modules"))).isFalse();
            assertThat(Files.exists(workspace.root().resolve("package-lock.json"))).isFalse();
        }
    }

    @Test
    void preparedArtifactCleanupStillWorks() {
        BuildWorkspace workspace = workspaceWithFiles("index.js", "console.log('hi')");

        PreparedArtifact artifact = builder.build(workspace);
        Path artifactDirectory = artifact.artifactDirectory();
        assertThat(Files.exists(artifactDirectory)).isTrue();

        artifact.close();

        assertThat(Files.exists(artifactDirectory)).isFalse();
    }

    private BuildWorkspace workspaceWithFiles(String... pathsAndContents) {
        return workspaceWithFiles(UUID.randomUUID(), pathsAndContents);
    }

    private BuildWorkspace workspaceWithFiles(UUID functionVersionId, String... pathsAndContents) {
        try {
            Path workspaceRoot = Files.createTempDirectory(tempDir, "workspace-");
            String entrypoint = pathsAndContents[0];
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                Path target = workspaceRoot.resolve(pathsAndContents[i]);
                Files.createDirectories(target.getParent());
                Files.writeString(target, pathsAndContents[i + 1]);
            }
            return new BuildWorkspace(functionVersionId, workspaceRoot, entrypoint, "NODE", "20");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static final class FakeProcessExecutor implements ProcessExecutor {
        private ProcessResult nextResult = new ProcessResult(0, "", "", false);
        private final List<List<String>> capturedCommands = new ArrayList<>();

        void nextResult(ProcessResult result) {
            this.nextResult = result;
        }

        @Override
        public ProcessResult execute(List<String> command, Path workingDirectory, Duration timeout) {
            capturedCommands.add(command);
            return nextResult;
        }

        int invocationCount() {
            return capturedCommands.size();
        }

        List<String> lastCommand() {
            return capturedCommands.get(capturedCommands.size() - 1);
        }
    }
}
