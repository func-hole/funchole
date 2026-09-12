package com.funchole.backend.controlplane.nodebuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.entity.SourceBundle;
import com.funchole.backend.controlplane.entity.SourceFile;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.FunctionRepository;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.controlplane.service.FunctionVersionLifecycleRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionSourceService;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class NodeBuildWorkspaceServiceTests {

    private static final List<String> FORBIDDEN_TRANSPORT_PACKAGE_PREFIXES = List.of(
            "jakarta.servlet",
            "org.springframework.web",
            "org.springframework.http",
            "com.funchole.backend.controlplane.controller"
    );

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("funchole")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private FunctionRepository functionRepository;

    @Autowired
    private FunctionVersionRepository functionVersionRepository;

    @Autowired
    private FunctionVersionSourceService sourceService;

    @Autowired
    private FunctionVersionLifecycleRegistry lifecycleRegistry;

    @Autowired
    private NodeBuildWorkspaceService buildWorkspaceService;

    @Test
    void multiFileSourceMaterializesCorrectly() {
        UUID functionVersionId = createPublishingVersionWithSource(new SourceBundle("NODE", "20", "index.js", List.of(
                new SourceFile("index.js", "console.log('hi')"),
                new SourceFile("package.json", "{}")
        )));

        try (NodeBuildWorkspace workspace = buildWorkspaceService.prepareWorkspace(functionVersionId)) {
            assertThat(workspace.functionVersionId()).isEqualTo(functionVersionId);
            assertThat(workspace.runtimeType()).isEqualTo("NODE");
            assertThat(workspace.runtimeVersion()).isEqualTo("20");
            assertThat(workspace.entrypoint()).isEqualTo("index.js");
            assertThat(readString(workspace.root().resolve("index.js"))).isEqualTo("console.log('hi')");
            assertThat(readString(workspace.root().resolve("package.json"))).isEqualTo("{}");
        }
    }

    @Test
    void nestedPathsArePreserved() {
        UUID functionVersionId = createPublishingVersionWithSource(new SourceBundle("NODE", null, "src/index.js", List.of(
                new SourceFile("src/index.js", "entry"),
                new SourceFile("lib/client.js", "client")
        )));

        try (NodeBuildWorkspace workspace = buildWorkspaceService.prepareWorkspace(functionVersionId)) {
            assertThat(readString(workspace.root().resolve("src/index.js"))).isEqualTo("entry");
            assertThat(readString(workspace.root().resolve("lib/client.js"))).isEqualTo("client");
        }
    }

    @Test
    void entrypointExistsInWorkspace() {
        UUID functionVersionId = createPublishingVersionWithSource(new SourceBundle("NODE", null, "src/index.js", List.of(
                new SourceFile("src/index.js", "entry"))));

        try (NodeBuildWorkspace workspace = buildWorkspaceService.prepareWorkspace(functionVersionId)) {
            assertThat(Files.isRegularFile(workspace.entrypointPath())).isTrue();
        }
    }

    @Test
    void missingSourceIsRejected() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());

        assertThatThrownBy(() -> buildWorkspaceService.prepareWorkspace(functionVersion.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void draftVersionCannotBeBuiltYet() {
        FunctionVersion functionVersion = createFunctionVersion();
        sourceService.submitSource(functionVersion.getId(), validBundle());

        assertThatThrownBy(() -> buildWorkspaceService.prepareWorkspace(functionVersion.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void invalidEntrypointIsRejected() {
        // submitSource already refuses a bundle whose entrypoint does not match
        // any file, so this exercises the build-time re-check directly with a
        // hand-built bundle that could never actually reach persistence.
        UUID functionVersionId = UUID.randomUUID();
        SourceBundle bundle = new SourceBundle("NODE", null, "missing.js", List.of(new SourceFile("index.js", "entry")));

        assertThatThrownBy(() -> buildWorkspaceService.materialize(functionVersionId, bundle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entrypoint");
    }

    @Test
    void workspaceCannotBeEscaped() {
        // Same rationale as invalidEntrypointIsRejected: submitSource already
        // rejects traversal paths, so this proves the build stage does not
        // blindly trust whatever SourceBundle it is handed either.
        UUID functionVersionId = UUID.randomUUID();
        SourceBundle bundle = new SourceBundle("NODE", null, "../escape.js", List.of(
                new SourceFile("../escape.js", "malicious")));

        assertThatThrownBy(() -> buildWorkspaceService.materialize(functionVersionId, bundle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void differentFunctionVersionsUseIsolatedWorkspaces() {
        UUID versionOneId = createPublishingVersionWithSource(new SourceBundle("NODE", null, "a.js", List.of(
                new SourceFile("a.js", "one"))));
        UUID versionTwoId = createPublishingVersionWithSource(new SourceBundle("NODE", null, "b.js", List.of(
                new SourceFile("b.js", "two"))));

        try (NodeBuildWorkspace workspaceOne = buildWorkspaceService.prepareWorkspace(versionOneId);
             NodeBuildWorkspace workspaceTwo = buildWorkspaceService.prepareWorkspace(versionTwoId)) {
            assertThat(workspaceOne.root()).isNotEqualTo(workspaceTwo.root());
            assertThat(Files.exists(workspaceOne.root().resolve("b.js"))).isFalse();
            assertThat(Files.exists(workspaceTwo.root().resolve("a.js"))).isFalse();
        }
    }

    @Test
    void temporaryWorkspaceCleanupWorks() {
        UUID functionVersionId = createPublishingVersionWithSource(validBundle());

        NodeBuildWorkspace workspace = buildWorkspaceService.prepareWorkspace(functionVersionId);
        Path root = workspace.root();
        assertThat(Files.exists(root)).isTrue();

        workspace.close();

        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    void applicationServiceHasNoHttpMcpCliDependency() {
        for (Field field : NodeBuildWorkspaceService.class.getDeclaredFields()) {
            assertTypeIsTransportNeutral(field.getType());
        }
        for (Constructor<?> constructor : NodeBuildWorkspaceService.class.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                assertTypeIsTransportNeutral(parameterType);
            }
        }
        for (Method method : NodeBuildWorkspaceService.class.getDeclaredMethods()) {
            assertTypeIsTransportNeutral(method.getReturnType());
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertTypeIsTransportNeutral(parameterType);
            }
        }
    }

    private void assertTypeIsTransportNeutral(Class<?> type) {
        String packageName = type.getPackageName();
        assertThat(FORBIDDEN_TRANSPORT_PACKAGE_PREFIXES.stream().anyMatch(packageName::startsWith))
                .as("type %s must not belong to a transport-specific package", type.getName())
                .isFalse();
    }

    private String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private SourceBundle validBundle() {
        return new SourceBundle("NODE", "20", "index.js", List.of(new SourceFile("index.js", "console.log('hi')")));
    }

    private UUID createPublishingVersionWithSource(SourceBundle sourceBundle) {
        FunctionVersion functionVersion = createFunctionVersion();
        sourceService.submitSource(functionVersion.getId(), sourceBundle);
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        return functionVersion.getId();
    }

    private FunctionVersion createFunctionVersion() {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        Function function = functionRepository.save(Function.create(
                admin,
                "fn_test_" + UUID.randomUUID().toString().replace("-", ""),
                "Test Function",
                "created by NodeBuildWorkspaceServiceTests",
                "NODE"
        ));
        return functionVersionRepository.save(FunctionVersion.create(function, 1, "NODE", null));
    }
}
