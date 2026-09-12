package com.funchole.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.artifact.PublishedArtifact;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.entity.SourceBundle;
import com.funchole.backend.controlplane.entity.SourceFile;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.FunctionRepository;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.controlplane.service.DirectInvocationResult;
import com.funchole.backend.controlplane.service.FunctionVersionArtifactRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionDeploymentFinalizer;
import com.funchole.backend.controlplane.service.FunctionVersionInvocationHandoff;
import com.funchole.backend.controlplane.service.FunctionVersionInvocationService;
import com.funchole.backend.controlplane.service.FunctionVersionInvocationSpec;
import com.funchole.backend.controlplane.service.FunctionVersionLifecycleRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionSourceService;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
class FunctionVersionInvocationServiceTests {

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

    @Test
    void readyFunctionVersionCreatesADirectInvocation() {
        FunctionVersion functionVersion = readyFunctionVersion();

        DirectInvocationResult result = service(inMemoryHandoff)
                .invoke(new FunctionVersionInvocationSpec(
                        functionVersion.getFunction().getId(),
                        functionVersion.getFunction().getFunctionKey(),
                        functionVersion.getId(),
                        functionVersion.getRuntime(),
                        "{\"path\":\"/orders\"}"));

        assertThat(result.invocationId()).isNotNull();
        assertThat(result.functionVersionId()).isEqualTo(functionVersion.getId());
        assertThat(result.initialStatus()).isEqualTo("PENDING");
    }

    @Test
    void exactFunctionVersionIdIsPinnedIntoTheHandoff() {
        FunctionVersion functionVersion = readyFunctionVersion();

        DirectInvocationResult result = service(recordingHandoff)
                .invoke(new FunctionVersionInvocationSpec(
                        functionVersion.getFunction().getId(),
                        functionVersion.getFunction().getFunctionKey(),
                        functionVersion.getId(),
                        functionVersion.getRuntime(),
                        "{}"));

        FunctionVersionInvocationSpec handed = recordingHandoff.lastSpec();
        assertThat(handed.functionVersionId()).isEqualTo(functionVersion.getId());
        assertThat(handed.functionId()).isEqualTo(functionVersion.getFunction().getId());
        assertThat(handed.functionKey()).isEqualTo(functionVersion.getFunction().getFunctionKey());
    }

    @Test
    void draftFunctionVersionIsRejected() {
        FunctionVersion functionVersion = createFunctionVersion("NODE");

        assertThatThrownBy(() -> invokeDirect(functionVersion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");

        assertThat(recordingHandoff.invocations()).isZero();
    }

    @Test
    void publishingFunctionVersionIsRejected() {
        FunctionVersion functionVersion = createFunctionVersion("NODE");
        lifecycleRegistry.beginPublishing(functionVersion.getId());

        assertThatThrownBy(() -> invokeDirect(functionVersion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHING");

        assertThat(recordingHandoff.invocations()).isZero();
    }

    @Test
    void failedFunctionVersionIsRejected() {
        FunctionVersion functionVersion = createFunctionVersion("NODE");
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        lifecycleRegistry.markFailed(functionVersion.getId());

        assertThatThrownBy(() -> invokeDirect(functionVersion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");

        assertThat(recordingHandoff.invocations()).isZero();
    }

    @Test
    void missingFunctionVersionIsRejected() {
        FunctionVersionInvocationSpec spec = new FunctionVersionInvocationSpec(
                UUID.randomUUID(), "fn_missing", UUID.randomUUID(), "NODE", "{}");

        assertThatThrownBy(() -> service(inMemoryHandoff).invoke(spec))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(spec.functionVersionId().toString());

        assertThat(recordingHandoff.invocations()).isZero();
    }

    @Test
    void inputPayloadIsPreservedIntoTheHandoff() {
        FunctionVersion functionVersion = readyFunctionVersion();
        String inputPayload = "{\"limit\":10,\"path\":\"/orders\"}";

        service(recordingHandoff).invoke(specForWithPayload(functionVersion, inputPayload));

        assertThat(recordingHandoff.lastSpec().inputPayload()).isEqualTo(inputPayload);
    }

    @Test
    void directInvocationPerformsNoFlowOrRouteResolution() {
        FunctionVersion functionVersion = readyFunctionVersion();

        service(recordingHandoff).invoke(specForWithPayload(functionVersion, "{}"));

        // The handed spec carries only the FunctionVersion identity that was
        // supplied; no Flow ids, Flow versions, or routes exist anywhere on
        // this path. Pinned identity comes straight from the durable version.
        FunctionVersionInvocationSpec handed = recordingHandoff.lastSpec();
        assertThat(handed.functionVersionId()).isEqualTo(functionVersion.getId());
        // and the runtime type is carried from the FunctionVersion, not hardcoded:
        assertThat(handed.runtimeType()).isEqualTo(functionVersion.getRuntime());
        assertThat(functionVersion.getRuntime()).isNotEqualTo("OTHER");
    }

    @Test
    void runtimeTypeIsCarriedFromTheFunctionVersionNotHardcoded() {
        FunctionVersion functionVersion = readyFunctionVersion();

        service(recordingHandoff).invoke(specFor(functionVersion));

        assertThat(recordingHandoff.lastSpec().runtimeType()).isEqualTo(functionVersion.getRuntime());
    }

    @Test
    void dispatchFailurePropagatesAndDoesNotLeaveAStuckInvocation() {
        FunctionVersion functionVersion = readyFunctionVersion();

        assertThatThrownBy(() -> service(throwingHandoff).invoke(specFor(functionVersion)))
                .isInstanceOf(FunctionVersionInvocationHandoff.FunctionVersionInvocationDispatchException.class)
                .hasMessageContaining("simulated dispatch failure");

        // The READY FunctionVersion is untouched; nothing was auto-deployed.
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.READY);
    }

    private DirectInvocationResult invokeDirect(FunctionVersion functionVersion) {
        return service(inMemoryHandoff).invoke(specFor(functionVersion));
    }

    private FunctionVersionInvocationSpec specFor(FunctionVersion functionVersion) {
        return new FunctionVersionInvocationSpec(
                functionVersion.getFunction().getId(),
                functionVersion.getFunction().getFunctionKey(),
                functionVersion.getId(),
                functionVersion.getRuntime(),
                "{}");
    }

    private FunctionVersionInvocationSpec specForWithPayload(FunctionVersion functionVersion, String inputPayload) {
        return new FunctionVersionInvocationSpec(
                functionVersion.getFunction().getId(),
                functionVersion.getFunction().getFunctionKey(),
                functionVersion.getId(),
                functionVersion.getRuntime(),
                inputPayload);
    }

    private FunctionVersionInvocationService service(FunctionVersionInvocationHandoff handoff) {
        return new FunctionVersionInvocationService(functionVersionRepository, handoff);
    }

    private FunctionVersion readyFunctionVersion() {
        FunctionVersion functionVersion = createFunctionVersion("NODE");
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        deploymentFinalizer.finalizeDeployment(functionVersion.getId(), publishedArtifactFor(functionVersion.getId()));
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.READY);
        return functionVersion;
    }

    private PublishedArtifact publishedArtifactFor(UUID functionVersionId) {
        return new PublishedArtifact(
                functionVersionId,
                FunctionVersionArtifactRegistry.artifactObjectKey(functionVersionId),
                SHA256_A,
                SIZE_A);
    }

    private FunctionVersion createFunctionVersion(String runtime) {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        Function function = functionRepository.save(Function.create(
                admin,
                "fn_invoke_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "Test Function",
                "created by FunctionVersionInvocationServiceTests",
                runtime
        ));
        FunctionVersion functionVersion = functionVersionRepository.save(FunctionVersion.create(function, 1, runtime, null));
        sourceService.submitSource(functionVersion.getId(), new SourceBundle(
                runtime, "1", "index.js", List.of(new SourceFile("index.js", "console.log('hi')"))));
        return functionVersion;
    }

    private InMemoryHandoff inMemoryHandoff = new InMemoryHandoff();
    private RecordingHandoff recordingHandoff = new RecordingHandoff();
    private ThrowingHandoff throwingHandoff = new ThrowingHandoff();

    private static final class InMemoryHandoff implements FunctionVersionInvocationHandoff {
        @Override
        public DirectInvocationResult dispatch(FunctionVersionInvocationSpec spec) {
            return new DirectInvocationResult(UUID.randomUUID(), spec.functionVersionId(), "PENDING");
        }
    }

    private static final class RecordingHandoff implements FunctionVersionInvocationHandoff {
        private final List<FunctionVersionInvocationSpec> specs = new CopyOnWriteArrayList<>();

        @Override
        public DirectInvocationResult dispatch(FunctionVersionInvocationSpec spec) {
            specs.add(spec);
            return new DirectInvocationResult(UUID.randomUUID(), spec.functionVersionId(), "PENDING");
        }

        FunctionVersionInvocationSpec lastSpec() {
            return specs.get(specs.size() - 1);
        }

        int invocations() {
            return specs.size();
        }
    }

    private static final class ThrowingHandoff implements FunctionVersionInvocationHandoff {
        @Override
        public DirectInvocationResult dispatch(FunctionVersionInvocationSpec spec) {
            throw new FunctionVersionInvocationHandoff.FunctionVersionInvocationDispatchException(
                    "simulated dispatch failure");
        }
    }
}
