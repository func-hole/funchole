package com.funchole.backend.controlplane.functionbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.controlplane.functionbuild.runtime.node.NodeRuntimeBuilder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RuntimeBuilderRegistryTests {

    private static final String NODE_SPECIFIC_PACKAGE = "com.funchole.backend.controlplane.functionbuild.runtime.node";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("funchole")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    private RuntimeBuilderRegistry runtimeBuilderRegistry;

    @Autowired
    private NodeRuntimeBuilder nodeRuntimeBuilder;

    @Test
    void resolverSelectsNodeBuilderForNodeRuntimeType() {
        RuntimeBuilder builder = runtimeBuilderRegistry.resolve("NODE");

        assertThat(builder).isSameAs(nodeRuntimeBuilder);
    }

    @Test
    void unsupportedRuntimeFailsWithClearStructuredError() {
        assertThatThrownBy(() -> runtimeBuilderRegistry.resolve("PYTHON"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PYTHON");
    }

    @Test
    void preparedArtifactOwnershipAndCleanupWorks(@TempDir Path tempDir) throws Exception {
        UUID functionVersionId = UUID.randomUUID();
        Path workspaceRoot = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspaceRoot.resolve("index.js"), "console.log('hi')");
        BuildWorkspace workspace = new BuildWorkspace(functionVersionId, workspaceRoot, "index.js", "NODE", "20");

        PreparedArtifact artifact = nodeRuntimeBuilder.build(workspace);

        assertThat(artifact.functionVersionId()).isEqualTo(functionVersionId);
        assertThat(artifact.entrypoint()).isEqualTo("index.js");
        assertThat(artifact.runtimeType()).isEqualTo("NODE");
        assertThat(artifact.runtimeVersion()).isEqualTo("20");
        assertThat(artifact.artifactDirectory()).isNotEqualTo(workspace.root());
        assertThat(Files.readString(artifact.artifactDirectory().resolve("index.js"))).isEqualTo("console.log('hi')");

        artifact.close();

        assertThat(Files.exists(artifact.artifactDirectory())).isFalse();
        // Closing the artifact must not touch the workspace it was built from -
        // ownership of the two temporary directories is independent.
        assertThat(Files.exists(workspaceRoot)).isTrue();
    }

    @Test
    void noNodeSpecificTypesLeakIntoGenericBuildOrchestration() {
        List<Class<?>> genericTypes = List.of(
                BuildWorkspace.class,
                BuildWorkspaceService.class,
                PreparedArtifact.class,
                RuntimeBuilder.class,
                RuntimeBuilderRegistry.class
        );
        for (Class<?> type : genericTypes) {
            for (Field field : type.getDeclaredFields()) {
                assertPackageIsNotNodeSpecific(field.getType());
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    assertPackageIsNotNodeSpecific(parameterType);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                assertPackageIsNotNodeSpecific(method.getReturnType());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertPackageIsNotNodeSpecific(parameterType);
                }
            }
        }
    }

    private void assertPackageIsNotNodeSpecific(Class<?> type) {
        assertThat(type.getPackageName())
                .as("type %s must not belong to the Node-specific runtime package", type.getName())
                .doesNotStartWith(NODE_SPECIFIC_PACKAGE);
    }
}
