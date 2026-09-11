package com.funchole.backend.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.funchole.backend.artifact.ArtifactReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemArtifactCacheTest {

    @TempDir
    Path cacheRoot;

    @Test
    void cacheMissThenPutResolvesTheExactPinnedArtifact() throws Exception {
        FilesystemArtifactCache cache = new FilesystemArtifactCache(cacheRoot, "NODE");
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        Path sourceArtifact = writeSourceArtifact(componentVersionId, "export async function handler() {}");

        assertTrue(cache.resolve(componentId, componentVersionId).isEmpty());

        ArtifactReference put = cache.put(componentId, componentVersionId, sourceArtifact.getParent());

        assertTrue(cache.resolve(componentId, componentVersionId).isPresent());
        ArtifactReference hit = cache.resolve(componentId, componentVersionId).orElseThrow();
        assertEquals(componentId, hit.componentId());
        assertEquals(componentVersionId, hit.componentVersionId());
        assertEquals("NODE", hit.runtimeType());
        assertTrue(hit.artifactPath().toString().endsWith("index.mjs"));
        assertEquals(put.artifactPath(), hit.artifactPath());
    }

    @Test
    void unknownVersionIsACacheMiss() {
        FilesystemArtifactCache cache = new FilesystemArtifactCache(cacheRoot, "NODE");

        assertTrue(cache.resolve(UUID.randomUUID(), UUID.randomUUID()).isEmpty());
    }

    @Test
    void differentComponentVersionsDoNotCollide() throws Exception {
        FilesystemArtifactCache cache = new FilesystemArtifactCache(cacheRoot, "NODE");
        UUID componentId = UUID.randomUUID();
        UUID versionA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        Path sourceA = writeSourceArtifact(versionA, "export async function handler() { return 'A'; }");
        Path sourceB = writeSourceArtifact(versionB, "export async function handler() { return 'B'; }");

        cache.put(componentId, versionA, sourceA.getParent());
        cache.put(componentId, versionB, sourceB.getParent());

        ArtifactReference hitA = cache.resolve(componentId, versionA).orElseThrow();
        ArtifactReference hitB = cache.resolve(componentId, versionB).orElseThrow();
        assertNotEquals(hitA.artifactPath(), hitB.artifactPath());
        assertTrue(hitA.artifactPath().toString().contains(versionA.toString()));
        assertTrue(hitB.artifactPath().toString().contains(versionB.toString()));
    }

    @Test
    void putIsIdempotentForTheSamePinnedVersion() throws Exception {
        FilesystemArtifactCache cache = new FilesystemArtifactCache(cacheRoot, "NODE");
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        Path sourceArtifact = writeSourceArtifact(componentVersionId, "export async function handler() { return { n: 1 }; }");
        Path artifactDirectory = sourceArtifact.getParent();

        ArtifactReference first = cache.put(componentId, componentVersionId, artifactDirectory);
        ArtifactReference second = cache.put(componentId, componentVersionId, artifactDirectory);

        assertEquals(first.artifactPath(), second.artifactPath());
        assertEquals(1, Files.walk(cacheRoot.resolve(componentVersionId.toString()))
                .filter(Files::isRegularFile)
                .count());
    }

    @Test
    void nestedArtifactDirectoriesArePreserved() throws Exception {
        FilesystemArtifactCache cache = new FilesystemArtifactCache(cacheRoot, "NODE");
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        UUID secondComponentVersionId = UUID.randomUUID();
        Path source = writeNestedSourceArtifact(componentVersionId);

        cache.put(componentId, componentVersionId, source.getParent());

        Path cacheEntry = cacheRoot.resolve(componentVersionId.toString());
        assertTrue(Files.isRegularFile(cacheEntry.resolve("index.mjs")));
        assertTrue(Files.isRegularFile(cacheEntry.resolve("lib/client.mjs")));
        assertTrue(Files.isRegularFile(cacheEntry.resolve("config/settings.json")));
        assertTrue(cache.resolve(componentId, componentVersionId).isPresent());

        // Same componentVersionId was reused as a source placeholder name
        // above; make the second distinct to prove no cross-version bleed.
        Path sourceB = writeNestedSourceArtifact(secondComponentVersionId);
        cache.put(componentId, secondComponentVersionId, sourceB.getParent());
        assertNotEquals(
                cache.resolve(componentId, componentVersionId).orElseThrow().artifactPath(),
                cache.resolve(componentId, secondComponentVersionId).orElseThrow().artifactPath());
    }

    @Test
    void sameFileNameInDifferentDirectoriesDoesNotCollide() throws Exception {
        FilesystemArtifactCache cache = new FilesystemArtifactCache(cacheRoot, "NODE");
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();

        Path directory = cacheRoot.resolve("source").resolve(componentVersionId.toString());
        Files.createDirectories(directory.resolve("lib"));
        Files.createDirectories(directory.resolve("vendor"));
        Files.writeString(directory.resolve("index.mjs"),
                "import { shared } from './lib/common.mjs'; export async function handler() { return shared; }");
        Files.writeString(directory.resolve("lib/common.mjs"), "export const shared = 'lib';");
        Files.writeString(directory.resolve("vendor/common.mjs"), "export const shared = 'vendor';");

        ArtifactReference reference = cache.put(componentId, componentVersionId, directory);

        assertEquals(reference.artifactPath(), cache.resolve(componentId, componentVersionId).orElseThrow().artifactPath());
        Path entryDirectory = cacheRoot.resolve(componentVersionId.toString());
        assertTrue(Files.readString(entryDirectory.resolve("lib/common.mjs")).contains("'lib'"));
        assertTrue(Files.readString(entryDirectory.resolve("vendor/common.mjs")).contains("'vendor'"));
        assertTrue(Files.isRegularFile(reference.artifactPath()));
    }

    @Test
    void cacheEntryIsNotObservableUntilAllNestedFilesAreMaterialized() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        Path source = writeNestedSourceArtifact(componentVersionId).getParent();
        CountDownLatch beforePublish = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        FilesystemArtifactCache cache = new FilesystemArtifactCache(cacheRoot, "NODE", () -> {
            beforePublish.countDown();
            try {
                if (!allowPublish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to publish artifact cache entry");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to publish artifact cache entry", exception);
            }
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> cache.put(componentId, componentVersionId, source));

            assertTrue(beforePublish.await(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(cacheRoot.resolve(componentVersionId.toString())));

            allowPublish.countDown();
            ArtifactReference reference = future.get(5, TimeUnit.SECONDS);
            Path cacheEntry = cacheRoot.resolve(componentVersionId.toString());
            assertEquals(cacheEntry.resolve("index.mjs").toAbsolutePath(), reference.artifactPath());
            assertTrue(Files.isRegularFile(cacheEntry.resolve("lib/client.mjs")));
            assertTrue(Files.isRegularFile(cacheEntry.resolve("config/settings.json")));
        }
    }

    private Path writeNestedSourceArtifact(UUID componentVersionId) throws IOException {
        Path directory = cacheRoot.resolve("source").resolve(componentVersionId.toString());
        Files.createDirectories(directory.resolve("lib"));
        Files.createDirectories(directory.resolve("config"));
        Files.writeString(directory.resolve("index.mjs"),
                "import { client } from './lib/client.mjs'; export async function handler() { return client; }");
        Files.writeString(directory.resolve("lib/client.mjs"), "export const client = 'client';");
        Files.writeString(directory.resolve("config/settings.json"), "{\"limit\":10}");
        return directory.resolve("index.mjs");
    }

    private Path writeSourceArtifact(UUID componentVersionId, String source) throws IOException {
        Path directory = cacheRoot.resolve("source").resolve(componentVersionId.toString());
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve("index.mjs"), source);
    }
}
