package com.funchole.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.ArtifactMetadata;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.FunctionRepository;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.controlplane.service.FunctionVersionArtifactRegistry;
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
class FunctionVersionArtifactPersistenceTests {

    private static final String SHA256_A = "a".repeat(64);
    private static final String SHA256_B = "b".repeat(64);
    private static final long SIZE_A = 1024L;
    private static final long SIZE_B = 2048L;

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

    @Test
    void artifactMetadataIsStoredForExactFunctionVersion() {
        FunctionVersion functionVersion = createFunctionVersion(1);
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());

        artifactRegistry.attachPublishedArtifact(
                functionVersion.getId(),
                objectKey,
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                SHA256_A,
                SIZE_A
        );

        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getArtifactMetadata()).hasValueSatisfying(metadata -> {
            assertThat(metadata.objectKey()).isEqualTo(objectKey);
            assertThat(metadata.format()).isEqualTo(FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ);
            assertThat(metadata.sha256()).isEqualTo(SHA256_A);
            assertThat(metadata.sizeBytes()).isEqualTo(SIZE_A);
            assertThat(metadata.publishedAt()).isNotNull();
        });
    }

    @Test
    void retrievingFunctionVersionReturnsSameArtifactReference() {
        FunctionVersion functionVersion = createFunctionVersion(1);
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        artifactRegistry.attachPublishedArtifact(
                functionVersion.getId(),
                objectKey,
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                SHA256_A,
                SIZE_A
        );

        ArtifactMetadata metadata = artifactRegistry.findArtifactMetadata(functionVersion.getId()).orElseThrow();

        assertThat(metadata.objectKey()).isEqualTo(objectKey);
        assertThat(metadata.format()).isEqualTo(FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ);
        assertThat(metadata.sha256()).isEqualTo(SHA256_A);
        assertThat(metadata.sizeBytes()).isEqualTo(SIZE_A);
    }

    @Test
    void differentVersionsCanReferenceDifferentArtifactObjects() {
        Function function = createFunction();
        FunctionVersion versionOne = functionVersionRepository.save(FunctionVersion.create(function, 1, "NODE", null));
        FunctionVersion versionTwo = functionVersionRepository.save(FunctionVersion.create(function, 2, "NODE", null));

        artifactRegistry.attachPublishedArtifact(
                versionOne.getId(),
                FunctionVersionArtifactRegistry.artifactObjectKey(versionOne.getId()),
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                SHA256_A,
                SIZE_A
        );
        artifactRegistry.attachPublishedArtifact(
                versionTwo.getId(),
                FunctionVersionArtifactRegistry.artifactObjectKey(versionTwo.getId()),
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                SHA256_B,
                SIZE_B
        );

        assertThat(functionVersionRepository.findById(versionOne.getId()).orElseThrow().getArtifactObjectKey())
                .isEqualTo("artifacts/" + versionOne.getId() + "/artifact.tar.gz");
        assertThat(functionVersionRepository.findById(versionTwo.getId()).orElseThrow().getArtifactObjectKey())
                .isEqualTo("artifacts/" + versionTwo.getId() + "/artifact.tar.gz");
        assertThat(functionVersionRepository.findById(versionOne.getId()).orElseThrow().getArtifactSha256())
                .isEqualTo(SHA256_A);
        assertThat(functionVersionRepository.findById(versionTwo.getId()).orElseThrow().getArtifactSha256())
                .isEqualTo(SHA256_B);
    }

    @Test
    void updatingOneVersionDoesNotAffectAnotherVersion() {
        Function function = createFunction();
        FunctionVersion versionOne = functionVersionRepository.save(FunctionVersion.create(function, 1, "NODE", null));
        FunctionVersion versionTwo = functionVersionRepository.save(FunctionVersion.create(function, 2, "NODE", null));

        artifactRegistry.attachPublishedArtifact(
                versionOne.getId(),
                FunctionVersionArtifactRegistry.artifactObjectKey(versionOne.getId()),
                FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ,
                SHA256_A,
                SIZE_A
        );

        assertThat(functionVersionRepository.findById(versionOne.getId()).orElseThrow().getArtifactMetadata()).isPresent();
        assertThat(functionVersionRepository.findById(versionTwo.getId()).orElseThrow().getArtifactMetadata()).isEmpty();
    }

    @Test
    void missingArtifactMetadataIsHandledExplicitly() {
        FunctionVersion functionVersion = createFunctionVersion(1);

        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getArtifactMetadata()).isEmpty();
        assertThat(artifactRegistry.findArtifactMetadata(functionVersion.getId())).isEmpty();
        assertThat(functionVersionRepository.findByIdAndArtifactObjectKeyIsNotNullAndArtifactFormatIsNotNull(
                functionVersion.getId())).isEmpty();
    }

    @Test
    void secondAttachmentForSameFunctionVersionIsRejected() {
        FunctionVersion functionVersion = createFunctionVersion(1);
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        artifactRegistry.attachPublishedArtifact(
                functionVersion.getId(), objectKey, FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ, SHA256_A, SIZE_A);

        assertThatThrownBy(() -> artifactRegistry.attachPublishedArtifact(
                functionVersion.getId(), objectKey, FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ, SHA256_B, SIZE_B))
                .isInstanceOf(IllegalStateException.class);

        // The original artifact metadata must survive the rejected second attempt untouched.
        FunctionVersion retrieved = functionVersionRepository.findById(functionVersion.getId()).orElseThrow();
        assertThat(retrieved.getArtifactMetadata()).hasValueSatisfying(metadata -> {
            assertThat(metadata.sha256()).isEqualTo(SHA256_A);
            assertThat(metadata.sizeBytes()).isEqualTo(SIZE_A);
        });
    }

    @Test
    void attachingArtifactWithMismatchedObjectKeyIsRejected() {
        FunctionVersion functionVersion = createFunctionVersion(1);
        String wrongObjectKey = FunctionVersionArtifactRegistry.artifactObjectKey(UUID.randomUUID());

        assertThatThrownBy(() -> artifactRegistry.attachPublishedArtifact(
                functionVersion.getId(), wrongObjectKey, FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ, SHA256_A, SIZE_A))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getArtifactMetadata()).isEmpty();
    }

    @Test
    void secondAttachmentWithIdenticalMetadataIsStillRejected() {
        FunctionVersion functionVersion = createFunctionVersion(1);
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        artifactRegistry.attachPublishedArtifact(
                functionVersion.getId(), objectKey, FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ, SHA256_A, SIZE_A);

        assertThatThrownBy(() -> artifactRegistry.attachPublishedArtifact(
                functionVersion.getId(), objectKey, FunctionVersionArtifactRegistry.ARTIFACT_FORMAT_TAR_GZ, SHA256_A, SIZE_A))
                .isInstanceOf(IllegalStateException.class);
    }

    private FunctionVersion createFunctionVersion(int version) {
        return functionVersionRepository.save(FunctionVersion.create(createFunction(), version, "NODE", null));
    }

    private Function createFunction() {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        return functionRepository.save(Function.create(
                admin,
                "fn_test_" + UUID.randomUUID().toString().replace("-", ""),
                "Test Function",
                "created by FunctionVersionArtifactPersistenceTests",
                "NODE"
        ));
    }
}
