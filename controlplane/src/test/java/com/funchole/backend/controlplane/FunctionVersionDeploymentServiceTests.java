package com.funchole.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.artifact.ArtifactPublisher;
import com.funchole.backend.artifact.PublishedArtifact;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.entity.SourceBundle;
import com.funchole.backend.controlplane.entity.SourceFile;
import com.funchole.backend.controlplane.functionbuild.BuildWorkspace;
import com.funchole.backend.controlplane.functionbuild.BuildWorkspaceService;
import com.funchole.backend.controlplane.functionbuild.PreparedArtifact;
import com.funchole.backend.controlplane.functionbuild.RuntimeBuilder;
import com.funchole.backend.controlplane.functionbuild.RuntimeBuilderRegistry;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.FunctionRepository;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.controlplane.service.FunctionVersionArtifactRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionDeploymentService;
import com.funchole.backend.controlplane.service.FunctionVersionLifecycleRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionSourceService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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
class FunctionVersionDeploymentServiceTests {

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
    private BuildWorkspaceService buildWorkspaceService;

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

        service(publisher).deploy(functionVersion.getId());

        assertThat(publisher.statusDuringPublish()).isEqualTo(FunctionVersionStatus.PUBLISHING);
    }

    @Test
    void successfulDeploymentEndsReadyAndPersistsMetadata() {
        FunctionVersion functionVersion = createFunctionVersion();
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId());
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(
                new PublishedArtifact(functionVersion.getId(), objectKey, SHA256_A, SIZE_A));

        FunctionVersion deployed = service(publisher).deploy(functionVersion.getId());

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

        service(publisher).deploy(functionVersion.getId());

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

        assertThatThrownBy(() -> service(publisher).deploy(functionVersion.getId()))
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

        assertThatThrownBy(() -> service(publisher).deploy(functionVersion.getId()))
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

        assertThatThrownBy(() -> service(publisher).deploy(functionVersion.getId()))
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
                .deploy(functionVersion.getId());
        RecordingArtifactPublisher secondAttemptPublisher = RecordingArtifactPublisher.returning(null);
        RecordingRuntimeBuilder secondAttemptBuilder = RecordingRuntimeBuilder.supporting("NODE");

        assertThatThrownBy(() -> service(secondAttemptPublisher, secondAttemptBuilder).deploy(functionVersion.getId()))
                .isInstanceOf(IllegalStateException.class);

        // Rejected before build or publish is ever attempted.
        assertThat(secondAttemptBuilder.invocationCount()).isZero();
        assertThat(secondAttemptPublisher.invocationCount()).isZero();
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.READY);
    }

    @Test
    void publishingVersionCannotDeployAgain() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(null);

        assertThatThrownBy(() -> service(publisher).deploy(functionVersion.getId()))
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
                .deploy(functionVersion.getId());

        assertThatThrownBy(() -> service(RecordingArtifactPublisher.returning(
                new PublishedArtifact(functionVersion.getId(), objectKey, "b".repeat(64), 2048L)))
                .deploy(functionVersion.getId()))
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

        assertThatThrownBy(() -> service(publisher).deploy(functionVersion.getId()))
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

        service(RecordingArtifactPublisher.returning(new PublishedArtifact(versionOne.getId(), objectKeyOne, SHA256_A, SIZE_A)))
                .deploy(versionOne.getId());
        assertThatThrownBy(() -> service(RecordingArtifactPublisher.throwing(new IllegalStateException("boom")))
                .deploy(versionTwo.getId()))
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

    @Test
    void buildWorkspaceIsCreatedFromTheExactFunctionVersionSource() {
        FunctionVersion functionVersion = createFunctionVersion();
        sourceService.submitSource(functionVersion.getId(), new SourceBundle("NODE", "20", "src/index.js", List.of(
                new SourceFile("src/index.js", "entry"),
                new SourceFile("lib/util.js", "util")
        )));
        RecordingRuntimeBuilder builder = RecordingRuntimeBuilder.supporting("NODE");
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(new PublishedArtifact(
                functionVersion.getId(), FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId()), SHA256_A, SIZE_A));

        service(publisher, builder).deploy(functionVersion.getId());

        assertThat(builder.capturedFiles()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "src/index.js", "entry",
                "lib/util.js", "util"
        ));
        assertThat(builder.capturedEntrypoint()).isEqualTo("src/index.js");
        assertThat(builder.capturedRuntimeType()).isEqualTo("NODE");
        assertThat(builder.capturedRuntimeVersion()).isEqualTo("20");
    }

    @Test
    void correctRuntimeBuilderIsSelectedByFunctionVersionRuntime() {
        FunctionVersion functionVersion = createFunctionVersion();
        RecordingRuntimeBuilder nodeBuilder = RecordingRuntimeBuilder.supporting("NODE");
        RecordingRuntimeBuilder otherBuilder = RecordingRuntimeBuilder.supporting("OTHER");
        RuntimeBuilderRegistry registry = new RuntimeBuilderRegistry(List.of(otherBuilder, nodeBuilder));
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(new PublishedArtifact(
                functionVersion.getId(), FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId()), SHA256_A, SIZE_A));
        FunctionVersionDeploymentService service = new FunctionVersionDeploymentService(
                buildWorkspaceService, registry, publisher, artifactRegistry, lifecycleRegistry);

        service.deploy(functionVersion.getId());

        assertThat(nodeBuilder.invocationCount()).isEqualTo(1);
        assertThat(otherBuilder.invocationCount()).isZero();
    }

    @Test
    void preparedArtifactIsPassedToArtifactPublisher() {
        FunctionVersion functionVersion = createFunctionVersion();
        RecordingRuntimeBuilder builder = RecordingRuntimeBuilder.supporting("NODE");
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(new PublishedArtifact(
                functionVersion.getId(), FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId()), SHA256_A, SIZE_A));

        service(publisher, builder).deploy(functionVersion.getId());

        assertThat(publisher.capturedArtifactDirectory()).isEqualTo(builder.lastArtifactDirectory());
    }

    @Test
    void workspaceIsCleanedAfterSuccess() {
        FunctionVersion functionVersion = createFunctionVersion();
        RecordingRuntimeBuilder builder = RecordingRuntimeBuilder.supporting("NODE");
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(new PublishedArtifact(
                functionVersion.getId(), FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId()), SHA256_A, SIZE_A));

        service(publisher, builder).deploy(functionVersion.getId());

        assertThat(Files.exists(builder.receivedWorkspaces().get(0).root())).isFalse();
    }

    @Test
    void preparedArtifactIsCleanedAfterSuccess() {
        FunctionVersion functionVersion = createFunctionVersion();
        RecordingRuntimeBuilder builder = RecordingRuntimeBuilder.supporting("NODE");
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(new PublishedArtifact(
                functionVersion.getId(), FunctionVersionArtifactRegistry.artifactObjectKey(functionVersion.getId()), SHA256_A, SIZE_A));

        service(publisher, builder).deploy(functionVersion.getId());

        assertThat(Files.exists(builder.lastArtifactDirectory())).isFalse();
    }

    @Test
    void buildFailureResultsInFailedAndPublisherIsNeverCalled() {
        FunctionVersion functionVersion = createFunctionVersion();
        RecordingRuntimeBuilder failingBuilder =
                RecordingRuntimeBuilder.throwing("NODE", new IllegalStateException("simulated build failure"));
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(null);

        assertThatThrownBy(() -> service(publisher, failingBuilder).deploy(functionVersion.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated build failure");

        assertThat(publisher.invocationCount()).isZero();
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.FAILED);
        // The workspace materialized before the failing build call must still be cleaned up.
        assertThat(failingBuilder.receivedWorkspaces()).hasSize(1);
        assertThat(Files.exists(failingBuilder.receivedWorkspaces().get(0).root())).isFalse();
    }

    @Test
    void cleanupHappensWhenPublisherFailsAfterASuccessfulBuild() {
        FunctionVersion functionVersion = createFunctionVersion();
        RecordingRuntimeBuilder builder = RecordingRuntimeBuilder.supporting("NODE");
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.throwing(
                new IllegalStateException("simulated upload failure"));

        assertThatThrownBy(() -> service(publisher, builder).deploy(functionVersion.getId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.exists(builder.receivedWorkspaces().get(0).root())).isFalse();
        assertThat(Files.exists(builder.lastArtifactDirectory())).isFalse();
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.FAILED);
    }

    @Test
    void unsupportedRuntimeResultsInFailed() {
        FunctionVersion functionVersion = createFunctionVersion("COBOL");
        RuntimeBuilderRegistry registry = new RuntimeBuilderRegistry(List.of(RecordingRuntimeBuilder.supporting("NODE")));
        RecordingArtifactPublisher publisher = RecordingArtifactPublisher.returning(null);
        FunctionVersionDeploymentService service = new FunctionVersionDeploymentService(
                buildWorkspaceService, registry, publisher, artifactRegistry, lifecycleRegistry);

        assertThatThrownBy(() -> service.deploy(functionVersion.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COBOL");

        assertThat(publisher.invocationCount()).isZero();
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.FAILED);
    }

    private FunctionVersionDeploymentService service(ArtifactPublisher publisher) {
        return service(publisher, RecordingRuntimeBuilder.supporting("NODE"));
    }

    private FunctionVersionDeploymentService service(ArtifactPublisher publisher, RuntimeBuilder runtimeBuilder) {
        return new FunctionVersionDeploymentService(
                buildWorkspaceService, new RuntimeBuilderRegistry(List.of(runtimeBuilder)), publisher, artifactRegistry, lifecycleRegistry);
    }

    private FunctionVersion createFunctionVersion() {
        return createFunctionVersion("NODE");
    }

    private FunctionVersion createFunctionVersion(String runtime) {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        Function function = functionRepository.save(Function.create(
                admin,
                "fn_test_" + UUID.randomUUID().toString().replace("-", ""),
                "Test Function",
                "created by FunctionVersionDeploymentServiceTests",
                runtime
        ));
        FunctionVersion functionVersion = functionVersionRepository.save(FunctionVersion.create(function, 1, runtime, null));
        sourceService.submitSource(functionVersion.getId(), new SourceBundle(
                runtime, "1", "index.js", List.of(new SourceFile("index.js", "console.log('hi')"))));
        return functionVersion;
    }

    private static final class RecordingArtifactPublisher implements ArtifactPublisher {
        private final PublishedArtifact result;
        private final RuntimeException failure;
        private final Supplier<FunctionVersionStatus> statusCapture;
        private final Runnable beforeThrow;
        private final List<UUID> invocations = new ArrayList<>();
        private FunctionVersionStatus capturedStatus;
        private Path capturedArtifactDirectory;

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
            capturedArtifactDirectory = preparedArtifactDirectory;
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

        Path capturedArtifactDirectory() {
            return capturedArtifactDirectory;
        }
    }

    /**
     * Records what {@link BuildWorkspace} it received and produces its own
     * fresh, real temporary artifact directory - distinct from the
     * workspace's - so cleanup-ownership tests can inspect both
     * independently after {@code deploy} returns.
     */
    private static final class RecordingRuntimeBuilder implements RuntimeBuilder {
        private final String runtimeType;
        private final RuntimeException failure;
        private final List<BuildWorkspace> receivedWorkspaces = new ArrayList<>();
        private Path artifactDirectory;
        private Map<String, String> capturedFiles;
        private String capturedEntrypoint;
        private String capturedRuntimeType;
        private String capturedRuntimeVersion;

        private RecordingRuntimeBuilder(String runtimeType, RuntimeException failure) {
            this.runtimeType = runtimeType;
            this.failure = failure;
        }

        static RecordingRuntimeBuilder supporting(String runtimeType) {
            return new RecordingRuntimeBuilder(runtimeType, null);
        }

        static RecordingRuntimeBuilder throwing(String runtimeType, RuntimeException failure) {
            return new RecordingRuntimeBuilder(runtimeType, failure);
        }

        @Override
        public boolean supports(String candidateRuntimeType) {
            return runtimeType.equalsIgnoreCase(candidateRuntimeType);
        }

        @Override
        public PreparedArtifact build(BuildWorkspace workspace) {
            receivedWorkspaces.add(workspace);
            // Captured up front - the workspace is closed by the caller as
            // soon as this method returns (or throws), so its content is not
            // safe to inspect afterward.
            capturedEntrypoint = workspace.entrypoint();
            capturedRuntimeType = workspace.runtimeType();
            capturedRuntimeVersion = workspace.runtimeVersion();
            capturedFiles = snapshotFiles(workspace.root());

            if (failure != null) {
                throw failure;
            }

            try {
                artifactDirectory = Files.createTempDirectory("test-prepared-artifact-");
                Path entrypointPath = artifactDirectory.resolve(workspace.entrypoint());
                Files.createDirectories(entrypointPath.getParent());
                Files.writeString(entrypointPath, "prepared");
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return new PreparedArtifact(
                    workspace.functionVersionId(), artifactDirectory, workspace.entrypoint(),
                    workspace.runtimeType(), workspace.runtimeVersion());
        }

        private Map<String, String> snapshotFiles(Path root) {
            try (var paths = Files.walk(root)) {
                Map<String, String> snapshot = new LinkedHashMap<>();
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    snapshot.put(root.relativize(path).toString(), Files.readString(path));
                }
                return snapshot;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        int invocationCount() {
            return receivedWorkspaces.size();
        }

        List<BuildWorkspace> receivedWorkspaces() {
            return receivedWorkspaces;
        }

        Path lastArtifactDirectory() {
            return artifactDirectory;
        }

        Map<String, String> capturedFiles() {
            return capturedFiles;
        }

        String capturedEntrypoint() {
            return capturedEntrypoint;
        }

        String capturedRuntimeType() {
            return capturedRuntimeType;
        }

        String capturedRuntimeVersion() {
            return capturedRuntimeVersion;
        }
    }
}
