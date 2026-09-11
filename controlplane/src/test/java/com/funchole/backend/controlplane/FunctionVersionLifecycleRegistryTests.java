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
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Deliberately NOT class-level {@code @Transactional}: the concurrency tests
 * here race real background threads against each other, each getting their
 * own connection/transaction, and need the setup row genuinely committed so
 * those other connections can see it - a test-transaction-and-rollback
 * fixture would either hide the row from the racing threads entirely or
 * silently roll back the very thing under test. Every test cleans up its own
 * rows explicitly instead.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FunctionVersionLifecycleRegistryTests {

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

    private final List<UUID> createdFunctionIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // functions -> function_versions is ON DELETE CASCADE, so this is enough.
        createdFunctionIds.forEach(functionRepository::deleteById);
        createdFunctionIds.clear();
    }

    @Test
    void normalDraftToPublishingTransition() {
        FunctionVersion functionVersion = createFunctionVersion();

        FunctionVersion result = lifecycleRegistry.beginPublishing(functionVersion.getId());

        assertThat(result.getStatus()).isEqualTo(FunctionVersionStatus.PUBLISHING);
        assertThat(functionVersionRepository.findById(functionVersion.getId()).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.PUBLISHING);
    }

    @Test
    void readyVersionCannotBeginPublishing() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        lifecycleRegistry.markReady(functionVersion.getId());

        assertThatThrownBy(() -> lifecycleRegistry.beginPublishing(functionVersion.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");
    }

    @Test
    void publishingVersionCannotBeginPublishing() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());

        assertThatThrownBy(() -> lifecycleRegistry.beginPublishing(functionVersion.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHING");
    }

    @Test
    void failedVersionCannotBeginPublishing() {
        FunctionVersion functionVersion = createFunctionVersion();
        lifecycleRegistry.beginPublishing(functionVersion.getId());
        lifecycleRegistry.markFailed(functionVersion.getId());

        assertThatThrownBy(() -> lifecycleRegistry.beginPublishing(functionVersion.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void missingFunctionVersionRemainsNotFound() {
        UUID missingId = UUID.randomUUID();

        assertThatThrownBy(() -> lifecycleRegistry.beginPublishing(missingId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void concurrentBeginPublishingCallsResultInExactlyOneSuccess() throws Exception {
        FunctionVersion functionVersion = createFunctionVersion();
        UUID functionVersionId = functionVersion.getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<FunctionVersion> attempt = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return lifecycleRegistry.beginPublishing(functionVersionId);
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<FunctionVersion> first = executor.submit(attempt);
            Future<FunctionVersion> second = executor.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            int rejections = 0;
            for (Future<FunctionVersion> future : List.of(first, second)) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException executionException) {
                    assertThat(executionException.getCause()).isInstanceOf(IllegalStateException.class);
                    rejections++;
                }
            }

            assertThat(successes).isEqualTo(1);
            assertThat(rejections).isEqualTo(1);
        }
        assertThat(functionVersionRepository.findById(functionVersionId).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.PUBLISHING);
    }

    @Test
    void concurrentDeployAttemptsCallArtifactPublisherExactlyOnce() throws Exception {
        FunctionVersion functionVersion = createFunctionVersion();
        UUID functionVersionId = functionVersion.getId();
        String objectKey = FunctionVersionArtifactRegistry.artifactObjectKey(functionVersionId);
        CountingArtifactPublisher publisher = new CountingArtifactPublisher(
                new PublishedArtifact(functionVersionId, objectKey, SHA256_A, SIZE_A));
        FunctionVersionDeploymentService service =
                new FunctionVersionDeploymentService(publisher, artifactRegistry, lifecycleRegistry);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<FunctionVersion> attempt = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return service.deployArtifact(functionVersionId, tempDir);
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<FunctionVersion> first = executor.submit(attempt);
            Future<FunctionVersion> second = executor.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            int rejections = 0;
            for (Future<FunctionVersion> future : List.of(first, second)) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException executionException) {
                    assertThat(executionException.getCause()).isInstanceOf(IllegalStateException.class);
                    rejections++;
                }
            }

            assertThat(successes).isEqualTo(1);
            assertThat(rejections).isEqualTo(1);
        }
        assertThat(publisher.invocationCount()).isEqualTo(1);
        assertThat(functionVersionRepository.findById(functionVersionId).orElseThrow().getStatus())
                .isEqualTo(FunctionVersionStatus.READY);
    }

    private FunctionVersion createFunctionVersion() {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        Function function = functionRepository.save(Function.create(
                admin,
                "fn_test_" + UUID.randomUUID().toString().replace("-", ""),
                "Test Function",
                "created by FunctionVersionLifecycleRegistryTests",
                "NODE"
        ));
        createdFunctionIds.add(function.getId());
        return functionVersionRepository.save(FunctionVersion.create(function, 1, "NODE", null));
    }

    private static final class CountingArtifactPublisher implements ArtifactPublisher {
        private final PublishedArtifact result;
        private final AtomicInteger invocationCount = new AtomicInteger();

        private CountingArtifactPublisher(PublishedArtifact result) {
            this.result = result;
        }

        @Override
        public PublishedArtifact publish(UUID componentVersionId, Path preparedArtifactDirectory) {
            invocationCount.incrementAndGet();
            return result;
        }

        int invocationCount() {
            return invocationCount.get();
        }
    }
}
