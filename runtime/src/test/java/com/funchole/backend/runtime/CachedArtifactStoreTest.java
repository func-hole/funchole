package com.funchole.backend.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.funchole.backend.artifact.ArtifactReference;
import com.funchole.backend.artifact.ArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cache hit/miss orchestration and same-process single-flight for cold
 * resolves - moved here from the old cache-aware S3ArtifactStore, now
 * exercised against a real FilesystemArtifactCache and a fake remote
 * ArtifactStore so it has no dependency on real S3.
 */
class CachedArtifactStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void cacheHitDoesNotCallRemoteStore() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        FilesystemArtifactCache cache = new FilesystemArtifactCache(tempDir.resolve("cache"), "NODE");
        Path source = writeArtifactDirectory("export async function handler() { return 'hit'; }");
        cache.put(componentId, componentVersionId, source);
        RecordingArtifactStore remoteStore = new RecordingArtifactStore();
        CachedArtifactStore store = new CachedArtifactStore(cache, remoteStore);

        Optional<ArtifactReference> resolved = store.resolve(componentId, componentVersionId);

        assertTrue(resolved.isPresent());
        assertEquals(0, remoteStore.resolveCount());
        assertEquals(cache.resolve(componentId, componentVersionId).orElseThrow().artifactPath(), resolved.get().artifactPath());
    }

    @Test
    void cacheMissRetrievesExactRemoteArtifactAndCachesIt() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        RecordingArtifactStore remoteStore = new RecordingArtifactStore();
        remoteStore.put(componentVersionId, "export async function handler() { return 'remote'; }");
        Path cacheRoot = tempDir.resolve("cache");
        CachedArtifactStore store = new CachedArtifactStore(new FilesystemArtifactCache(cacheRoot, "NODE"), remoteStore);

        ArtifactReference resolved = store.resolve(componentId, componentVersionId).orElseThrow();

        assertEquals(1, remoteStore.resolveCount());
        assertEquals(cacheRoot.resolve(componentVersionId.toString()).resolve("index.mjs"), resolved.artifactPath());
        assertTrue(Files.isRegularFile(resolved.artifactPath()));
        // The remote store's temporary directory must be cleaned up once materialized into the cache.
        assertTrue(remoteStore.lastReturnedDirectoryWasDeleted());
    }

    @Test
    void missingRemoteArtifactKeepsArtifactNotFoundBehavior() {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        RecordingArtifactStore remoteStore = new RecordingArtifactStore();
        CachedArtifactStore store = new CachedArtifactStore(
                new FilesystemArtifactCache(tempDir.resolve("cache"), "NODE"), remoteStore);

        Optional<ArtifactReference> resolved = store.resolve(componentId, componentVersionId);

        assertTrue(resolved.isEmpty());
        assertEquals(1, remoteStore.resolveCount());
    }

    @Test
    void concurrentMissesForSameVersionPerformOneRemoteFetch() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        RecordingArtifactStore remoteStore = new RecordingArtifactStore();
        remoteStore.put(componentVersionId, "export async function handler() { return 'single-flight'; }");
        remoteStore.blockResolutions(1);
        Path cacheRoot = tempDir.resolve("cache");
        CachedArtifactStore store = new CachedArtifactStore(new FilesystemArtifactCache(cacheRoot, "NODE"), remoteStore);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<ArtifactReference> task = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return store.resolve(componentId, componentVersionId).orElseThrow();
            };
            var first = executor.submit(task);
            var second = executor.submit(task);

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(remoteStore.awaitResolutionsStarted());
            assertEquals(1, remoteStore.resolveCount());
            remoteStore.releaseResolutions();

            ArtifactReference firstReference = first.get(5, TimeUnit.SECONDS);
            ArtifactReference secondReference = second.get(5, TimeUnit.SECONDS);
            assertEquals(firstReference.artifactPath(), secondReference.artifactPath());
            assertEquals(1, remoteStore.resolveCount());
        }
    }

    @Test
    void differentComponentVersionsResolveIndependently() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID versionA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        RecordingArtifactStore remoteStore = new RecordingArtifactStore();
        remoteStore.put(versionA, "export async function handler() { return 'A'; }");
        remoteStore.put(versionB, "export async function handler() { return 'B'; }");
        CachedArtifactStore store = new CachedArtifactStore(
                new FilesystemArtifactCache(tempDir.resolve("cache"), "NODE"), remoteStore);

        ArtifactReference artifactA = store.resolve(componentId, versionA).orElseThrow();
        ArtifactReference artifactB = store.resolve(componentId, versionB).orElseThrow();

        assertTrue(artifactA.artifactPath().toString().contains(versionA.toString()));
        assertTrue(artifactB.artifactPath().toString().contains(versionB.toString()));
        assertTrue(Files.readString(artifactA.artifactPath()).contains("'A'"));
        assertTrue(Files.readString(artifactB.artifactPath()).contains("'B'"));
        assertEquals(2, remoteStore.resolveCount());
    }

    private Path writeArtifactDirectory(String source) throws IOException {
        Path directory = Files.createTempDirectory(tempDir, "source-");
        Files.writeString(directory.resolve("index.mjs"), source);
        return directory;
    }

    /**
     * Fake remote {@link ArtifactStore}: mirrors the real contract that
     * {@code S3ArtifactStore} now provides - each resolve materializes a
     * fresh temporary directory that the caller (CachedArtifactStore) is
     * expected to delete after consuming it.
     */
    private final class RecordingArtifactStore implements ArtifactStore {
        private final java.util.Map<UUID, String> sourcesByVersion = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger resolveCount = new AtomicInteger();
        private volatile Path lastReturnedDirectory;
        private CountDownLatch resolutionsStarted;
        private CountDownLatch releaseResolutions;

        private void put(UUID componentVersionId, String source) {
            sourcesByVersion.put(componentVersionId, source);
        }

        private void blockResolutions(int expectedCount) {
            resolutionsStarted = new CountDownLatch(expectedCount);
            releaseResolutions = new CountDownLatch(1);
        }

        @Override
        public Optional<ArtifactReference> resolve(UUID componentId, UUID componentVersionId) {
            resolveCount.incrementAndGet();
            if (resolutionsStarted != null) {
                resolutionsStarted.countDown();
            }
            if (releaseResolutions != null) {
                try {
                    if (!releaseResolutions.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release fake remote resolution");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to release fake remote resolution", exception);
                }
            }
            String source = sourcesByVersion.get(componentVersionId);
            if (source == null) {
                return Optional.empty();
            }
            try {
                Path directory = Files.createTempDirectory("fake-remote-" + componentVersionId + "-");
                Files.writeString(directory.resolve("index.mjs"), source);
                lastReturnedDirectory = directory;
                return Optional.of(new ArtifactReference(componentId, componentVersionId, "NODE", directory.resolve("index.mjs")));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write fake remote artifact", exception);
            }
        }

        private int resolveCount() {
            return resolveCount.get();
        }

        private boolean lastReturnedDirectoryWasDeleted() {
            return lastReturnedDirectory != null && !Files.exists(lastReturnedDirectory);
        }

        private boolean awaitResolutionsStarted() throws InterruptedException {
            return resolutionsStarted == null || resolutionsStarted.await(5, TimeUnit.SECONDS);
        }

        private void releaseResolutions() {
            if (releaseResolutions != null) {
                releaseResolutions.countDown();
            }
        }
    }
}
