package com.funchole.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.FunctionRepository;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.artifact.ArtifactPublisher;
import com.funchole.backend.artifact.PublishedArtifact;
import com.funchole.backend.controlplane.service.FunctionVersionArtifactRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionDeploymentService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    @Test
    void unpublishedFunctionVersionPublishesAndPersistsMetadata() {
        FunctionVersion functionVersion = createFunctionVersion();
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(
                new PublishedArtifact(functionVersion.getId(), objectKey, SHA256_A, SIZE_A));
        FunctionVersionDeploymentService service = new FunctionVersionDeploymentService(publisher, artifactRegistry);

        FunctionVersion deployed = service.deployArtifact(functionVersion.getId(), tempDir);

        assertThat(publisher.invocationCount()).isEqualTo(1);
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
        FunctionVersionDeploymentService service = new FunctionVersionDeploymentService(publisher, artifactRegistry);

        service.deployArtifact(functionVersion.getId(), tempDir);

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getArtifactObjectKey()).isEqualTo(objectKey);
        assertThat(retrieved.getArtifactSha256()).isEqualTo(sha256);
        assertThat(retrieved.getArtifactSizeBytes()).isEqualTo(size);
    }

    @Test
    void alreadyPublishedFunctionVersionIsRejectedBeforePublisherIsCalled() {
        FunctionVersion functionVersion = createFunctionVersion();
        artifactRegistry.attachPublishedArtifact(
                functionVersion.getId(),
                FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId()),
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                SHA256_A,
                SIZE_A
        );
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(null);
        FunctionVersionDeploymentService service = new FunctionVersionDeploymentService(publisher, artifactRegistry);

        assertThatThrownBy(() -> service.deployArtifact(functionVersion.getId(), tempDir))
                .isInstanceOf(IllegalStateException.class);

        assertThat(publisher.invocationCount()).isZero();
    }

    @Test
    void publisherFailureLeavesFunctionVersionWithoutArtifactMetadata() {
        FunctionVersion functionVersion = createFunctionVersion();
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.throwing(
                new IllegalStateException("simulated upload failure"));
        FunctionVersionDeploymentService service = new FunctionVersionDeploymentService(publisher, artifactRegistry);

        assertThatThrownBy(() -> service.deployArtifact(functionVersion.getId(), tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated upload failure");

        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getArtifactMetadata())
                .isEmpty();
    }

    @Test
    void mismatchedPublishedArtifactVersionIdIsRejected() {
        FunctionVersion functionVersion = createFunctionVersion();
        UUID differentVersionId = UUID.randomUUID();
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(new PublishedArtifact(
                differentVersionId,
                FunctionVersionArtifactRegistry.artifactObjectKey(differentVersionId),
                SHA256_A,
                SIZE_A
        ));
        FunctionVersionDeploymentService service = new FunctionVersionDeploymentService(publisher, artifactRegistry);

        assertThatThrownBy(() -> service.deployArtifact(functionVersion.getId(), tempDir))
                .isInstanceOf(IllegalStateException.class);

        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getArtifactMetadata())
                .isEmpty();
    }

    @Test
    void differentFunctionVersionsDeployIndependently() {
        FunctionVersion versionOne = createFunctionVersion();
        FunctionVersion versionTwo = createFunctionVersion();
        String objectKeyOne = FunctionVersionArtifactRegistry.artifactObjectKey(versionOne.getId());
        String objectKeyTwo = FunctionVersionArtifactRegistry.artifactObjectKey(versionTwo.getId());

        FunctionVersionDeploymentService serviceOne = new FunctionVersionDeploymentService(
                RecordingArtifactPublisher.returning(new PublishedArtifact(versionOne.getId(), objectKeyOne, SHA256_A, SIZE_A)),
                artifactRegistry);
        FunctionVersionDeploymentService serviceTwo = new FunctionVersionDeploymentService(
                RecordingArtifactPublisher.returning(new PublishedArtifact(versionTwo.getId(), objectKeyTwo, "b".repeat(64), 2048L)),
                artifactRegistry);

        serviceOne.deployArtifact(versionOne.getId(), tempDir);
        serviceTwo.deployArtifact(versionTwo.getId(), tempDir);

        assertThat(functionVersionRepository.findById(versionOne.getId()).orElseThrow().getArtifactSha256())
                .isEqualTo(SHA256_A);
        assertThat(functionVersionRepository.findById(versionTwo.getId()).orElseThrow().getArtifactSha256())
                .isEqualTo("b".repeat(64));
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
        private final List<UUID> invocations = new ArrayList<>();

        private RecordingArtifactPublisher(PublishedArtifact result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        static RecordingArtifactPublisher returning(PublishedArtifact result) {
            return new RecordingArtifactPublisher(result, null);
        }

        static RecordingArtifactPublisher throwing(RuntimeException failure) {
            return new RecordingArtifactPublisher(null, failure);
        }

        @Override
        public PublishedArtifact publish(UUID componentVersionId, Path preparedArtifactDirectory) {
            invocations.add(componentVersionId);
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        int invocationCount() {
            return invocations.size();
        }
    }
}
