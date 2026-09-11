package com.funchole.backend.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
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

    private Path writeSourceArtifact(UUID componentVersionId, String source) throws IOException {
        Path directory = cacheRoot.resolve("source").resolve(componentVersionId.toString());
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve("index.mjs"), source);
    }
}
