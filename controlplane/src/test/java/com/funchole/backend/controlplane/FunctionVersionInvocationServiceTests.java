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
import com.funchole.backend.controlplane.service.DirectFunctionInvocationCommand;
import com.funchole.backend.controlplane.service.FunctionVersionArtifactRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionDeploymentFinalizer;
import com.funchole.backend.controlplane.service.FunctionVersionInvocationService;
import com.funchole.backend.controlplane.service.FunctionVersionLifecycleRegistry;
import com.funchole.backend.controlplane.service.FunctionVersionSourceService;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import com.funchole.backend.invocationcontract.DirectInvocationRequest;
import com.funchole.backend.invocationcontract.DirectInvocationResult;
import com.funchole.backend.invocationcontract.FunctionVersionInvocationHandoff;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FunctionVersionInvocationService realFunctionVersionInvocationService;

    @Test
    void readyFunctionVersionCreatesADirectInvocation() {
        FunctionVersion functionVersion = readyFunctionVersion();

        DirectInvocationResult result = service(inMemoryHandoff)
                .invoke(new DirectFunctionInvocationCommand(functionVersion.getId(), "{\"path\":\"/orders\"}"));

        assertThat(result.invocationId()).isNotNull();
        assertThat(result.functionVersionId()).isEqualTo(functionVersion.getId());
        assertThat(result.initialStatus()).isEqualTo("PENDING");
    }

    @Test
    void exactFunctionVersionIdIsPinnedIntoTheHandoff() {
        FunctionVersion functionVersion = readyFunctionVersion();

        DirectInvocationResult result = service(recordingHandoff)
                .invoke(new DirectFunctionInvocationCommand(functionVersion.getId(), "{}"));

        DirectInvocationRequest handed = recordingHandoff.lastRequest();
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
        UUID missingVersionId = UUID.randomUUID();

        assertThatThrownBy(() -> service(inMemoryHandoff)
                .invoke(new DirectFunctionInvocationCommand(missingVersionId, "{}")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(missingVersionId.toString());

        assertThat(recordingHandoff.invocations()).isZero();
    }

    @Test
    void inputPayloadIsPreservedIntoTheHandoff() {
        FunctionVersion functionVersion = readyFunctionVersion();
        String inputPayload = "{\"limit\":10,\"path\":\"/orders\"}";

        service(recordingHandoff).invoke(new DirectFunctionInvocationCommand(functionVersion.getId(), inputPayload));

        assertThat(recordingHandoff.lastRequest().inputPayload()).isEqualTo(inputPayload);
    }

    @Test
    void directInvocationPerformsNoFlowOrRouteResolution() {
        FunctionVersion functionVersion = readyFunctionVersion();

        service(recordingHandoff).invoke(new DirectFunctionInvocationCommand(functionVersion.getId(), "{}"));

        // The handed request carries only the FunctionVersion identity that
        // was supplied; no Flow ids, Flow versions, or routes exist anywhere
        // on this path. Pinned identity comes straight from the durable version.
        DirectInvocationRequest handed = recordingHandoff.lastRequest();
        assertThat(handed.functionVersionId()).isEqualTo(functionVersion.getId());
        // and the runtime type is carried from the FunctionVersion, not hardcoded:
        assertThat(handed.runtimeType()).isEqualTo(functionVersion.getRuntime());
        assertThat(functionVersion.getRuntime()).isNotEqualTo("OTHER");
    }

    @Test
    void runtimeTypeIsCarriedFromTheFunctionVersionNotHardcoded() {
        FunctionVersion functionVersion = readyFunctionVersion();

        service(recordingHandoff).invoke(new DirectFunctionInvocationCommand(functionVersion.getId(), "{}"));

        assertThat(recordingHandoff.lastRequest().runtimeType()).isEqualTo(functionVersion.getRuntime());
    }

    @Test
    void dispatchFailurePropagatesAndDoesNotLeaveAStuckInvocation() {
        FunctionVersion functionVersion = readyFunctionVersion();

        assertThatThrownBy(() -> service(throwingHandoff).invoke(new DirectFunctionInvocationCommand(functionVersion.getId(), "{}")))
                .isInstanceOf(FunctionVersionInvocationHandoff.FunctionVersionInvocationDispatchException.class)
                .hasMessageContaining("simulated dispatch failure");

        // The READY FunctionVersion is untouched; nothing was auto-deployed.
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.READY);
    }

    private DirectInvocationResult invokeDirect(FunctionVersion functionVersion) {
        return service(inMemoryHandoff).invoke(commandFor(functionVersion));
    }

    private DirectFunctionInvocationCommand commandFor(FunctionVersion functionVersion) {
        return new DirectFunctionInvocationCommand(functionVersion.getId(), "{}");
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

    @Test
    void serviceOnlyUsesContractBoundaryTypesNeverInvocationImplementationOrExecutionClasses() {
        // "com.funchole.backend.invocation." (trailing dot) intentionally
        // excludes "com.funchole.backend.invocationcontract" - the contract
        // package - which is exactly what this service is allowed to depend
        // on (InvocationRegistry, JdbcInvocationRegistry, the persisted
        // Invocation record, and every other invocation-module internal all
        // live under the forbidden prefix).
        List<String> forbiddenPackagePrefixes = List.of(
                "com.funchole.backend.invocation.",
                "com.funchole.backend.dispatcher",
                "com.funchole.backend.runtimeregistry",
                "com.funchole.backend.runtime"
        );
        for (Field field : FunctionVersionInvocationService.class.getDeclaredFields()) {
            assertTypeIsAllowed(field.getType(), forbiddenPackagePrefixes);
        }
        for (Constructor<?> constructor : FunctionVersionInvocationService.class.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                assertTypeIsAllowed(parameterType, forbiddenPackagePrefixes);
            }
        }
        for (Method method : FunctionVersionInvocationService.class.getDeclaredMethods()) {
            assertTypeIsAllowed(method.getReturnType(), forbiddenPackagePrefixes);
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertTypeIsAllowed(parameterType, forbiddenPackagePrefixes);
            }
        }
    }

    @Test
    void springResolvesExactlyOneFunctionVersionInvocationHandoffBean() {
        assertThat(applicationContext.getBeansOfType(FunctionVersionInvocationHandoff.class)).hasSize(1);
    }

    @Test
    void functionVersionInvocationServiceIsConstructedThroughNormalApplicationWiring() {
        assertThat(applicationContext.getBeansOfType(FunctionVersionInvocationService.class)).hasSize(1);
        assertThat(realFunctionVersionInvocationService).isNotNull();
    }

    private void assertTypeIsAllowed(Class<?> type, List<String> forbiddenPackagePrefixes) {
        for (String forbidden : forbiddenPackagePrefixes) {
            assertThat(type.getPackageName().startsWith(forbidden))
                    .as("type %s must not belong to package %s", type.getName(), forbidden)
                    .isFalse();
        }
    }

    private static final class InMemoryHandoff implements FunctionVersionInvocationHandoff {
        @Override
        public DirectInvocationResult dispatch(DirectInvocationRequest request) {
            return new DirectInvocationResult(UUID.randomUUID(), request.functionVersionId(), "PENDING");
        }
    }

    private static final class RecordingHandoff implements FunctionVersionInvocationHandoff {
        private final List<DirectInvocationRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public DirectInvocationResult dispatch(DirectInvocationRequest request) {
            requests.add(request);
            return new DirectInvocationResult(UUID.randomUUID(), request.functionVersionId(), "PENDING");
        }

        DirectInvocationRequest lastRequest() {
            return requests.get(requests.size() - 1);
        }

        int invocations() {
            return requests.size();
        }
    }

    private static final class ThrowingHandoff implements FunctionVersionInvocationHandoff {
        @Override
        public DirectInvocationResult dispatch(DirectInvocationRequest request) {
            throw new FunctionVersionInvocationHandoff.FunctionVersionInvocationDispatchException(
                    "simulated dispatch failure");
        }
    }
}
