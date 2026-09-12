package com.funchole.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.artifact.PublishedArtifact;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.FunctionRepository;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.controlplane.service.FunctionVersionArtifactRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionDeploymentFinalizer;
import com.funchole.backend.controlplane.service.FunctionVersionLifecycleRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionSourceService;
import com.funchole.backend.controlplane.entity.SourceBundle;
import com.funchole.backend.controlplane.entity.SourceFile;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Focused tests for the atomic deployment finalization boundary.
 * Integration behavior across the whole deploy pipeline lives in
 * {@link FunctionVersionDeploymentServiceTests}; these tests pin the
 * finalizer's own invariant: metadata + READY commit together or not at all.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FunctionVersionDeploymentFinalizerTests {

    private static final String SHA256_A = "a".repeat(64);
    private static final long SIZE_A = 1024L;

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
    private FunctionVersionDeploymentFinalizer deploymentFinalizer;

    @Autowired
    private FunctionVersionLifecycleRegistry lifecycleRegistry;

    @Autowired
    private FunctionVersionArtifactRegistry artifactRegistry;

    @Test
    void commitsMetadataAndReadyStatusTogether() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        PublishedArtifact published = publishedArtifactFor(functionVersion.getId());

        FunctionVersion finalized = deploymentFinalizer.finalizeDeployment(functionVersion.getId(), published);

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(finalized.getStatus()).isEqualTo(FunctionVersionStatus.READY);
        assertThat(retrieved.getStatus()).isEqualTo(FunctionVersionStatus.READY);
        assertThat(retrieved.getArtifactObjectKey()).isEqualTo(published.objectKey());
        assertThat(retrieved.getArtifactSha256()).isEqualTo(SHA256_A);
        assertThat(retrieved.getArtifactSizeBytes()).isEqualTo(SIZE_A);
    }

    @Test
    void invalidArtifactMetadataRollsBackBeforeAnyPersistence() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        PublishedArtifact invalidSha = new PublishedArtifact(
                functionVersion.getId(),
                FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId()),
                "not-a-valid-sha256",
                SIZE_A);

        assertThatThrownBy(() -> deploymentFinalizer.finalizeDeployment(functionVersion.getId(), invalidSha))
                .isInstanceOf(IllegalArgumentException.class);

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(FunctionVersionStatus.PUBLISHING);
        assertThat(retrieved.getArtifactMetadata()).isEmpty();
    }

    @Test
    void wrongPublishedArtifactVersionIdIsRejectedBeforeCommit() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        UUID otherVersionId = UUID.randomUUID();
        PublishedArtifact foreign = new PublishedArtifact(
                otherVersionId,
                FunctionVersionArtifactRegistry.artifactObjectKey(otherVersionId),
                SHA256_A,
                SIZE_A);

        assertThatThrownBy(() -> deploymentFinalizer.finalizeDeployment(functionVersion.getId(), foreign))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(otherVersionId.toString());

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(FunctionVersionStatus.PUBLISHING);
        assertThat(retrieved.getArtifactMetadata()).isEmpty();
    }

    @Test
    void alreadyFinalizedFunctionVersionCannotBeFinalizedAgain() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        PublishedArtifact published = publishedArtifactFor(functionVersion.getId());
        deploymentFinalizer.finalizeDeployment(functionVersion.getId(), published);

        PublishedArtifact second = new PublishedArtifact(
                functionVersion.getId(),
                FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId()),
                "b".repeat(64),
                2048L);

        assertThatThrownBy(() -> deploymentFinalizer.finalizeDeployment(functionVersion.getId(), second))
                .isInstanceOf(IllegalStateException.class);

        // The first deployment's metadata remains the only, immutable artifact reference.
        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getArtifactSha256()).isEqualTo(SHA256_A);
        assertThat(retrieved.getArtifactSizeBytes()).isEqualTo(SIZE_A);
    }

    @Test
    void publishedArtifactWithWrongObjectKeyIsRejectedBeforeCommit() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        PublishedArtifact mutatedObjectKey = new PublishedArtifact(
                functionVersion.getId(),
                "artifacts/" + functionVersion.getId() + "/tampered.tar.gz",
                SHA256_A,
                SIZE_A);

        assertThatThrownBy(() -> deploymentFinalizer.finalizeDeployment(functionVersion.getId(), mutatedObjectKey))
                .isInstanceOf(IllegalArgumentException.class);

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(FunctionVersionStatus.PUBLISHING);
        assertThat(retrieved.getArtifactMetadata()).isEmpty();
    }

    private PublishedArtifact publishedArtifactFor(UUID functionVersionId) {
        return new PublishedArtifact(
                functionVersionId,
                FunctionVersionArtifactRegistry.artifactObjectKey(functionVersionId),
                SHA256_A,
                SIZE_A);
    }

    private FunctionVersion createFunctionVersion() {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        Function function = functionRepository.save(Function.create(
                admin,
                "fn_finalizer_" + UUID.randomUUID().toString().replace("-", ""),
                "Test Function",
                "created by FunctionVersionDeploymentFinalizerTests",
                "NODE"
        ));
        FunctionVersion functionVersion = functionVersionRepository.save(FunctionVersion.create(function, 1, "NODE", null));
        sourceService.submitSource(functionVersion.getId(), new SourceBundle(
                "NODE", "1", "index.js", List.of(new SourceFile("index.js", "console.log('hi')"))));
        return functionVersion;
    }
}
