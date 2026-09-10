package com.funchole.backend.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryArtifactResolverTest {

    @TempDir
    Path artifactsRoot;

    @Test
    void resolvesExactRegisteredComponentVersion() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        writeArtifact(componentVersionId, "export async function handler(input) { return { ok: true }; }");
        ArtifactResolver resolver = new DirectoryArtifactResolver(artifactsRoot, "NODE");

        Optional<ArtifactReference> resolved = resolver.resolve(componentId, componentVersionId);

        assertTrue(resolved.isPresent());
        assertEquals(componentId, resolved.get().componentId());
        assertEquals(componentVersionId, resolved.get().componentVersionId());
        assertEquals("NODE", resolved.get().runtimeType());
        assertTrue(resolved.get().artifactPath().toString().endsWith("index.mjs"));
    }

    @Test
    void pinnedVersionStaysStableWhenANewerVersionIsRegistered() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID versionA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        writeArtifact(versionA, "export async function handler() { return 'A'; }");
        writeArtifact(versionB, "export async function handler() { return 'B'; }");
        ArtifactResolver resolver = new DirectoryArtifactResolver(artifactsRoot, "NODE");

        Optional<ArtifactReference> resolved = resolver.resolve(componentId, versionA);

        assertTrue(resolved.isPresent());
        assertEquals(versionA, resolved.get().componentVersionId());
        assertTrue(resolved.get().artifactPath().toString().contains(versionA.toString()));
        assertTrue(!resolved.get().artifactPath().toString().contains(versionB.toString()));
    }

    @Test
    void resolvesEmptyForUnknownComponentVersion() {
        ArtifactResolver resolver = new DirectoryArtifactResolver(artifactsRoot, "NODE");

        Optional<ArtifactReference> resolved = resolver.resolve(UUID.randomUUID(), UUID.randomUUID());

        assertTrue(resolved.isEmpty());
    }

    private void writeArtifact(UUID componentVersionId, String source) throws IOException {
        Path directory = artifactsRoot.resolve(componentVersionId.toString());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("index.mjs"), source);
    }
}
