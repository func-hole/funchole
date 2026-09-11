package com.funchole.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.artifact.ArtifactPublisher;
import com.funchole.backend.artifact.PublishedArtifact;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.FunctionRepository;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.controlplane.service.FunctionVersionArtifactRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionDeploymentService;
import com.funchole.backend.controlplane.service.FunctionVersionLifecycleRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
class FunctionVersionDeploymentServiceTests {

    private static final String SHA256_A = "a".repeat(64);
    private static final long SIZE_A = 1024L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("funchole")
            .withUsername("test")
            .withPassword("test");

    @TempDir
    Path tempDir;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private FunctionRepository functionRepository;

    @Autowired
    private FunctionVersionRepository functionVersionRepository;

    @Autowired
    private FunctionVersionArtifactRegistry artifactRegistry;

    @Autowired
    private FunctionVersionLifecycleRegistry lifecycleRegistry;

    @Test
    void newFunctionVersionStartsDraft() {
        FunctionVersion functionVersion = createFunctionVersion();

        assertThat(functionVersion.getStatus()).isEqualTo(FunctionVersionStatus.DRAFT);
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.DRAFT);
    }

    @Test
    void deploymentSetsPublishingBeforePublisherIsInvoked() {
        FunctionVersion functionVersion = createFunctionVersion();
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.capturingStatusAndReturning(
                () -> functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus(),
                new PublishedArtifact(functionVersion.getId(), objectKey, SHA256_A, SIZE_A));
        FunctionVersionDeploymentService service = service(publisher);

        service.deployArtifact(functionVersion.getId(), tempDir);

        assertThat(publisher.statusDuringPublish()).isEqualTo(FunctionVersionStatus.PUBLISHING);
    }

    @Test
    void successfulDeploymentEndsReadyAndPersistsMetadata() {
        FunctionVersion functionVersion = createFunctionVersion();
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(
                new PublishedArtifact(functionVersion.getId(), objectKey, SHA256_A, SIZE_A));
        FunctionVersionDeploymentService service = service(publisher);

        FunctionVersion deployed = service.deployArtifact(functionVersion.getId(), tempDir);

        assertThat(publisher.invocationCount()).isEqualTo(1);
        assertThat(deployed.getStatus()).isEqualTo(FunctionVersionStatus.READY);
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.READY);
        assertThat(deployed.getArtifactMetadata()).hasValueSatisfying(metadata -> {
            assertThat(metadata.objectKey()).isEqualTo(objectKey);
            assertThat(metadata.sha256()).isEqualTo(SHA256_A);
            assertThat(metadata.sizeBytes()).isEqualTo(SIZE_A);
        });
    }

    @Test
    void persistedMetadataComesFromThePublishedArtifact() {
        FunctionVersion functionVersion = createFunctionVersion();
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        String sha256 = "c".repeat(64);
        long size = 777L;
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(
                new PublishedArtifact(functionVersion.getId(), objectKey, sha256, size));
        FunctionVersionDeploymentService service = service(publisher);

        service.deployArtifact(functionVersion.getId(), tempDir);

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getArtifactObjectKey()).isEqualTo(objectKey);
        assertThat(retrieved.getArtifactSha256()).isEqualTo(sha256);
        assertThat(retrieved.getArtifactSizeBytes()).isEqualTo(size);
    }

    @Test
    void publisherFailureEndsFailedAndLeavesFunctionVersionWithoutArtifactMetadata() {
        FunctionVersion functionVersion = createFunctionVersion();
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.throwing(
                new IllegalStateException("simulated upload failure"));
        FunctionVersionDeploymentService service = service(publisher);

        assertThatThrownBy(() -> service.deployArtifact(functionVersion.getId(), tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated upload failure");

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(FunctionVersionStatus.FAILED);
        assertThat(retrieved.getArtifactMetadata()).isEmpty();
    }

    @Test
    void artifactMetadataPersistenceFailureEndsFailed() {
        FunctionVersion functionVersion = createFunctionVersion();
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        // An invalid sha256 makes FunctionVersionArtifactRegistry.attachPublishedArtifact
        // itself reject the write - a registration/persistence failure, not a publish failure.
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(
                new PublishedArtifact(functionVersion.getId(), objectKey, "not-a-valid-sha256", SIZE_A));
        FunctionVersionDeploymentService service = service(publisher);

        assertThatThrownBy(() -> service.deployArtifact(functionVersion.getId(), tempDir))
                .isInstanceOf(IllegalArgumentException.class);

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(FunctionVersionStatus.FAILED);
        assertThat(retrieved.getArtifactMetadata()).isEmpty();
    }

    @Test
    void originalPublishExceptionIsPreservedIfMarkFailedAlsoFails() {
        FunctionVersion functionVersion = createFunctionVersion();
        IllegalStateException originalFailure = new IllegalStateException("simulated publish failure");
        // Moves the version out of PUBLISHING before throwing, so the deployment
        // service's own markFailed(...) call (which requires PUBLISHING) fails too.
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.throwingAfter(
                () -> lifecycleRegistry.markReady(functionVersion.getId()), originalFailure);
        FunctionVersionDeploymentService service = service(publisher);

        assertThatThrownBy(() -> service.deployArtifact(functionVersion.getId(), tempDir))
                .isSameAs(originalFailure)
                .satisfies(thrown -> {
                    assertThat(thrown.getSuppressed()).hasSize(1);
                    assertThat(thrown.getSuppressed()[0])
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("must be PUBLISHING");
                });
    }

    @Test
    void readyVersionCannotDeployAgain() {
        FunctionVersion functionVersion = createFunctionVersion();
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        service(RecordingArtifactPublisher.returning(new PublishedArtifact(functionVersion.getId(), objectKey, SHA256_A, SIZE_A)))
                .deployArtifact(functionVersion.getId(), tempDir);
        RecordingArtifactPublisher secondAttemptPublisher = RecordingArtifactPublisher.returning(null);

        assertThatThrownBy(() -> service(secondAttemptPublisher).deployArtifact(functionVersion.getId(), tempDir))
                .isInstanceOf(IllegalStateException.class);

        assertThat(secondAttemptPublisher.invocationCount()).isZero();
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.READY);
    }

    @Test
    void publishingVersionCannotDeployAgain() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(null);

        assertThatThrownBy(() -> service(publisher).deployArtifact(functionVersion.getId(), tempDir))
                .isInstanceOf(IllegalStateException.class);

        assertThat(publisher.invocationCount()).isZero();
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.PUBLISHING);
    }

    @Test
    void artifactMetadataRemainsImmutableAcrossARejectedSecondAttempt() {
        FunctionVersion functionVersion = createFunctionVersion();
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        service(RecordingArtifactPublisher.returning(new PublishedArtifact(functionVersion.getId(), objectKey, SHA256_A, SIZE_A)))
                .deployArtifact(functionVersion.getId(), tempDir);

        assertThatThrownBy(() -> service(RecordingArtifactPublisher.returning(
                new PublishedArtifact(functionVersion.getId(), objectKey, "b".repeat(64), 2048L)))
                .deployArtifact(functionVersion.getId(), tempDir))
                .isInstanceOf(IllegalStateException.class);

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getArtifactSha256()).isEqualTo(SHA256_A);
        assertThat(retrieved.getArtifactSizeBytes()).isEqualTo(SIZE_A);
    }

    @Test
    void mismatchedPublishedArtifactVersionIdIsRejectedAndEndsFailed() {
        FunctionVersion functionVersion = createFunctionVersion();
        UUID differentVersionId = UUID.randomUUID();
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(new PublishedArtifact(
                differentVersionId,
                FunctionVersionArtifactRegistry.artifactObjectKey(differentVersionId),
                SHA256_A,
                SIZE_A
        ));
        FunctionVersionDeploymentService service = service(publisher);

        assertThatThrownBy(() -> service.deployArtifact(functionVersion.getId(), tempDir))
                .isInstanceOf(IllegalStateException.class);

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(FunctionVersionStatus.FAILED);
        assertThat(retrieved.getArtifactMetadata()).isEmpty();
    }

    @Test
    void differentFunctionVersionsDeployAndMaintainIndependentStates() {
        FunctionVersion versionOne = createFunctionVersion();
        FunctionVersion versionTwo = createFunctionVersion();
        String objectKeyOne = FunctionVersionArtifactRegistry.artifactObjectKey(versionOne.getId());
        String objectKeyTwo = FunctionVersionArtifactRegistry.artifactObjectKey(versionTwo.getId());

        service(RecordingArtifactPublisher.returning(new PublishedArtifact(versionOne.getId(), objectKeyOne, SHA256_A, SIZE_A)))
                .deployArtifact(versionOne.getId(), tempDir);
        assertThatThrownBy(() -> service(RecordingArtifactPublisher.throwing(new IllegalStateException("boom")))
                .deployArtifact(versionTwo.getId(), tempDir))
                .isInstanceOf(IllegalStateException.class);

        assertThat(functionVersionRepository.findById(versionOne.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.READY);
        assertThat(functionVersionRepository.findById(versionOne.getId()).orElseThrow().getArtifactSha256())
                .isEqualTo(SHA256_A);
        assertThat(functionVersionRepository.findById(versionTwo.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.FAILED);
        assertThat(functionVersionRepository.findById(versionTwo.getId()).orElseThrow().getArtifactMetadata())
                .isEmpty();
    }

    private FunctionVersionDeploymentService service(ArtifactPublisher publisher) {
        return new FunctionVersionDeploymentService(publisher, artifactRegistry, lifecycleRegistry);
    }

    private FunctionVersion createFunctionVersion() {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        Function function = functionRepository.save(Function.create(
                admin,
                "fn_test_" + UUID.randomUUID().toString().replace("-", ""),
                "Test Function",
                "created by FunctionVersionDeploymentServiceTests",
                "NODE"
        ));
        return functionVersionRepository.save(FunctionVersion.create(function, 1, "NODE", null));
    }

    private static final class RecordingArtifactPublisher implements ArtifactPublisher {
        private final PublishedArtifact result;
        private final RuntimeException failure;
        private final Supplier<FunctionVersionStatus> statusCapture;
        private final Runnable beforeThrow;
        private final List<UUID> invocations = new ArrayList<>();
        private FunctionVersionStatus capturedStatus;

        private RecordingArtifactPublisher(
                PublishedArtifact result, RuntimeException failure, Supplier<FunctionVersionStatus> statusCapture, Runnable beforeThrow) {
            this.result = result;
            this.failure = failure;
            this.statusCapture = statusCapture;
            this.beforeThrow = beforeThrow;
        }

        static RecordingArtifactPublisher returning(PublishedArtifact result) {
            return new RecordingArtifactPublisher(result, null, null, null);
        }

        static RecordingArtifactPublisher throwing(RuntimeException failure) {
            return new RecordingArtifactPublisher(null, failure, null, null);
        }

        static RecordingArtifactPublisher throwingAfter(Runnable beforeThrow, RuntimeException failure) {
            return new RecordingArtifactPublisher(null, failure, null, beforeThrow);
        }

        static RecordingArtifactPublisher capturingStatusAndReturning(Supplier<FunctionVersionStatus> statusCapture, PublishedArtifact result) {
            return new RecordingArtifactPublisher(result, null, statusCapture, null);
        }

        @Override
        public PublishedArtifact publish(UUID componentVersionId, Path preparedArtifactDirectory) {
            invocations.add(componentVersionId);
            if (statusCapture != null) {
                capturedStatus = statusCapture.get();
            }
            if (beforeThrow != null) {
                beforeThrow.run();
            }
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        int invocationCount() {
            return invocations.size();
        }

        FunctionVersionStatus statusDuringPublish() {
            return capturedStatus;
        }
    }
}
