package com.funchole.backend.controlplane;

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
import com.funchole.backend.controlplane.service.FunctionVersionArtifactRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionLifecycleRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionSourceService;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
class FunctionVersionSourceServiceTests {

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
    private FunctionVersionArtifactRegistry artifactRegistry;

    @Autowired
    private FunctionVersionSourceService sourceService;

    @Autowired
    private FunctionVersionLifecycleRegistry lifecycleRegistry;

    @Test
    void validMultiFileSourceSubmissionIsStoredAndReadableBack() {
        FunctionVersion functionVersion = createFunctionVersion();
        SourceBundle bundle = new SourceBundle("NODE", "20", "index.js", List.of(
                new SourceFile("index.js", "console.log('hi')"),
                new SourceFile("lib/util.js", "module.exports = {}")
        ));

        sourceService.submitSource(functionVersion.getId(), bundle);
        SourceBundle retrieved = sourceService.findSource(functionVersion.getId()).orElseThrow();

        assertThat(retrieved.runtimeType()).isEqualTo("NODE");
        assertThat(retrieved.runtimeVersion()).isEqualTo("20");
        assertThat(retrieved.entrypoint()).isEqualTo("index.js");
        assertThat(retrieved.files()).containsExactlyInAnyOrder(
                new SourceFile("index.js", "console.log('hi')"),
                new SourceFile("lib/util.js", "module.exports = {}")
        );
    }

    @Test
    void sourceBelongsToExactFunctionVersion() {
        FunctionVersion functionVersion = createFunctionVersion();
        UUID otherFunctionVersionId = createFunctionVersion().getId();
        SourceBundle bundle = validBundle();

        sourceService.submitSource(functionVersion.getId(), bundle);

        assertThat(sourceService.findSource(functionVersion.getId())).isPresent();
        assertThat(sourceService.findSource(otherFunctionVersionId)).isEmpty();
    }

    @Test
    void differentFunctionVersionsHaveIndependentSource() {
        FunctionVersion versionOne = createFunctionVersion();
        FunctionVersion versionTwo = createFunctionVersion();

        sourceService.submitSource(versionOne.getId(), new SourceBundle("NODE", null, "a.js", List.of(
                new SourceFile("a.js", "one"))));
        sourceService.submitSource(versionTwo.getId(), new SourceBundle("NODE", null, "b.js", List.of(
                new SourceFile("b.js", "two"))));

        assertThat(sourceService.findSource(versionOne.getId()).orElseThrow().entrypoint()).isEqualTo("a.js");
        assertThat(sourceService.findSource(versionTwo.getId()).orElseThrow().entrypoint()).isEqualTo("b.js");
    }

    @Test
    void missingFunctionVersionIsRejected() {
        assertThatThrownBy(() -> sourceService.submitSource(UUID.randomUUID(), validBundle()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nestedRelativePathsAreAccepted() {
        FunctionVersion functionVersion = createFunctionVersion();
        SourceBundle bundle = new SourceBundle("NODE", null, "src/handlers/index.js", List.of(
                new SourceFile("src/handlers/index.js", "handler"),
                new SourceFile("src/handlers/nested/deep/helper.js", "helper")
        ));

        sourceService.submitSource(functionVersion.getId(), bundle);

        assertThat(sourceService.findSource(functionVersion.getId()).orElseThrow().files())
                .extracting(SourceFile::relativePath)
                .containsExactlyInAnyOrder("src/handlers/index.js", "src/handlers/nested/deep/helper.js");
    }

    @Test
    void absolutePathsAreRejected() {
        FunctionVersion functionVersion = createFunctionVersion();
        SourceBundle bundle = new SourceBundle("NODE", null, "/index.js", List.of(
                new SourceFile("/index.js", "console.log('hi')")));

        assertThatThrownBy(() -> sourceService.submitSource(functionVersion.getId(), bundle))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void traversalPathsAreRejected() {
        FunctionVersion functionVersion = createFunctionVersion();
        SourceBundle bundle = new SourceBundle("NODE", null, "../index.js", List.of(
                new SourceFile("../index.js", "console.log('hi')")));

        assertThatThrownBy(() -> sourceService.submitSource(functionVersion.getId(), bundle))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicatePathsAreRejected() {
        FunctionVersion functionVersion = createFunctionVersion();
        SourceBundle bundle = new SourceBundle("NODE", null, "index.js", List.of(
                new SourceFile("index.js", "one"),
                new SourceFile("index.js", "two")
        ));

        assertThatThrownBy(() -> sourceService.submitSource(functionVersion.getId(), bundle))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingEntrypointIsRejected() {
        FunctionVersion functionVersion = createFunctionVersion();
        SourceBundle bundle = new SourceBundle("NODE", null, "missing.js", List.of(
                new SourceFile("index.js", "console.log('hi')")));

        assertThatThrownBy(() -> sourceService.submitSource(functionVersion.getId(), bundle))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void artifactMetadataRemainsIndependentFromSource() {
        FunctionVersion functionVersion = createFunctionVersion();
        sourceService.submitSource(functionVersion.getId(), validBundle());

        artifactRegistry.attachPublishedArtifact(
                functionVersion.getId(),
                FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId()),
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                "a".repeat(64),
                1024L
        );

        assertThat(sourceService.findSource(functionVersion.getId())).isPresent();
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getArtifactMetadata())
                .isPresent();
        assertThat(sourceService.findSource(functionVersion.getId()).orElseThrow().entrypoint())
                .isEqualTo(validBundle().entrypoint());
    }

    @Test
    void draftVersionAllowsIterativeSourceReplacement() {
        FunctionVersion functionVersion = createFunctionVersion();

        sourceService.submitSource(functionVersion.getId(), new SourceBundle("NODE", null, "a.js", List.of(
                new SourceFile("a.js", "version A"))));
        sourceService.submitSource(functionVersion.getId(), new SourceBundle("NODE", null, "b.js", List.of(
                new SourceFile("b.js", "version B"))));
        sourceService.submitSource(functionVersion.getId(), new SourceBundle("NODE", null, "c.js", List.of(
                new SourceFile("c.js", "version C"))));

        SourceBundle retrieved = sourceService.findSource(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.entrypoint()).isEqualTo("c.js");
        assertThat(retrieved.files()).containsExactly(new SourceFile("c.js", "version C"));
    }

    @Test
    void publishingVersionRejectsSourceModification() {
        FunctionVersion functionVersion = createFunctionVersion();
        sourceService.submitSource(functionVersion.getId(), validBundle());
        lifecycleRegistry.beginPublishing(functionVersion.getId());

        assertThatThrownBy(() -> sourceService.submitSource(functionVersion.getId(), new SourceBundle(
                "NODE", null, "other.js", List.of(new SourceFile("other.js", "rejected")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHING");
    }

    @Test
    void readyVersionRejectsSourceModification() {
        FunctionVersion functionVersion = createFunctionVersion();
        sourceService.submitSource(functionVersion.getId(), validBundle());
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        lifecycleRegistry.markReady(functionVersion.getId());

        assertThatThrownBy(() -> sourceService.submitSource(functionVersion.getId(), new SourceBundle(
                "NODE", null, "other.js", List.of(new SourceFile("other.js", "rejected")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");
    }

    @Test
    void failedVersionRejectsSourceModification() {
        FunctionVersion functionVersion = createFunctionVersion();
        sourceService.submitSource(functionVersion.getId(), validBundle());
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        lifecycleRegistry.markFailed(functionVersion.getId());

        assertThatThrownBy(() -> sourceService.submitSource(functionVersion.getId(), new SourceBundle(
                "NODE", null, "other.js", List.of(new SourceFile("other.js", "rejected")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void rejectedSourceModificationLeavesPreviousSourceUnchanged() {
        FunctionVersion functionVersion = createFunctionVersion();
        sourceService.submitSource(functionVersion.getId(), validBundle());
        lifecycleRegistry.beginPublishing(functionVersion.getId());

        assertThatThrownBy(() -> sourceService.submitSource(functionVersion.getId(), new SourceBundle(
                "NODE", null, "other.js", List.of(new SourceFile("other.js", "rejected")))))
                .isInstanceOf(IllegalStateException.class);

        SourceBundle retrieved = sourceService.findSource(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.runtimeType()).isEqualTo(validBundle().runtimeType());
        assertThat(retrieved.runtimeVersion()).isEqualTo(validBundle().runtimeVersion());
        assertThat(retrieved.entrypoint()).isEqualTo(validBundle().entrypoint());
        assertThat(retrieved.files()).containsExactlyElementsOf(validBundle().files());
    }

    @Test
    void artifactMetadataRemainsUnchangedAfterRejectedSourceModification() {
        FunctionVersion functionVersion = createFunctionVersion();
        sourceService.submitSource(functionVersion.getId(), validBundle());
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        artifactRegistry.attachPublishedArtifact(
                functionVersion.getId(),
                FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId()),
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                "a".repeat(64),
                1024L
        );
        lifecycleRegistry.markReady(functionVersion.getId());

        assertThatThrownBy(() -> sourceService.submitSource(functionVersion.getId(), new SourceBundle(
                "NODE", null, "other.js", List.of(new SourceFile("other.js", "rejected")))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getArtifactMetadata())
                .hasValueSatisfying(metadata -> assertThat(metadata.sha256()).isEqualTo("a".repeat(64)));
    }

    @Test
    void applicationServiceHasNoHttpMcpCliDependency() {
        for (Field field : FunctionVersionSourceService.class.getDeclaredFields()) {
            assertTypeIsTransportNeutral(field.getType());
        }
        for (Constructor<?> constructor : FunctionVersionSourceService.class.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                assertTypeIsTransportNeutral(parameterType);
            }
        }
        for (Method method : FunctionVersionSourceService.class.getDeclaredMethods()) {
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

    private SourceBundle validBundle() {
        return new SourceBundle("NODE", "20", "index.js", List.of(new SourceFile("index.js", "console.log('hi')")));
    }

    private FunctionVersion createFunctionVersion() {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        Function function = functionRepository.save(Function.create(
                admin,
                "fn_test_" + UUID.randomUUID().toString().replace("-", ""),
                "Test Function",
                "created by FunctionVersionSourceServiceTests",
                "NODE"
        ));
        return functionVersionRepository.save(FunctionVersion.create(function, 1, "NODE", null));
    }
}
