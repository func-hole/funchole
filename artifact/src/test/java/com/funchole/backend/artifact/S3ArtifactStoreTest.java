package com.funchole.backend.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * S3ArtifactStore is exact-remote-retrieval only now - no cache, no
 * single-flight de-duplication. Those behaviors are covered by
 * CachedArtifactStoreTest in the runtime module, against a real
 * FilesystemArtifactCache and a fake remote ArtifactStore.
 */
class S3ArtifactStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesExactPinnedComponentVersionFromRemote() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        Path archive = writeArchive(Map.of("index.mjs", "export async function handler() { return 'remote'; }"));
        RecordingS3ArtifactClient s3Client = new RecordingS3ArtifactClient();
        s3Client.put(S3ArtifactStore.objectKey(componentVersionId), archive);
        S3ArtifactStore store = new S3ArtifactStore("NODE", s3Client);

        ArtifactReference resolved = store.resolve(componentId, componentVersionId).orElseThrow();

        assertEquals(componentId, resolved.componentId());
        assertEquals(componentVersionId, resolved.componentVersionId());
        assertEquals("NODE", resolved.runtimeType());
        assertEquals(1, s3Client.downloadCount());
        assertEquals(S3ArtifactStore.objectKey(componentVersionId), s3Client.lastKey());
        assertTrue(Files.isRegularFile(resolved.artifactPath()));
        assertTrue(resolved.artifactPath().toString().endsWith("index.mjs"));
    }

    @Test
    void preservesNestedArtifactDirectories() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        Path archive = writeArchive(Map.of(
                "index.mjs", "import { value } from './lib/value.mjs'; export async function handler() { return value; }",
                "lib/value.mjs", "export const value = 42;",
                "config/settings.json", "{\"enabled\":true}"
        ));
        RecordingS3ArtifactClient s3Client = new RecordingS3ArtifactClient();
        s3Client.put(S3ArtifactStore.objectKey(componentVersionId), archive);
        S3ArtifactStore store = new S3ArtifactStore("NODE", s3Client);

        ArtifactReference resolved = store.resolve(componentId, componentVersionId).orElseThrow();

        Path extractedDirectory = resolved.artifactPath().getParent();
        assertTrue(Files.isRegularFile(extractedDirectory.resolve("lib/value.mjs")));
        assertTrue(Files.isRegularFile(extractedDirectory.resolve("config/settings.json")));
    }

    @Test
    void missingRemoteArtifactResolvesEmpty() {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        RecordingS3ArtifactClient s3Client = new RecordingS3ArtifactClient();
        S3ArtifactStore store = new S3ArtifactStore("NODE", s3Client);

        Optional<ArtifactReference> resolved = store.resolve(componentId, componentVersionId);

        assertTrue(resolved.isEmpty());
        assertEquals(1, s3Client.downloadCount());
        assertEquals(S3ArtifactStore.objectKey(componentVersionId), s3Client.lastKey());
    }

    @Test
    void differentComponentVersionsRemainIsolated() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID versionA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        RecordingS3ArtifactClient s3Client = new RecordingS3ArtifactClient();
        s3Client.put(S3ArtifactStore.objectKey(versionA), writeArchive(Map.of("index.mjs", "export async function handler() { return 'A'; }")));
        s3Client.put(S3ArtifactStore.objectKey(versionB), writeArchive(Map.of("index.mjs", "export async function handler() { return 'B'; }")));
        S3ArtifactStore store = new S3ArtifactStore("NODE", s3Client);

        ArtifactReference artifactA = store.resolve(componentId, versionA).orElseThrow();
        ArtifactReference artifactB = store.resolve(componentId, versionB).orElseThrow();

        assertTrue(Files.readString(artifactA.artifactPath()).contains("'A'"));
        assertTrue(Files.readString(artifactB.artifactPath()).contains("'B'"));
        assertEquals(2, s3Client.downloadCount());
    }

    @Test
    void eachResolveDownloadsIndependentlyWithNoDeduplication() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        RecordingS3ArtifactClient s3Client = new RecordingS3ArtifactClient();
        s3Client.put(S3ArtifactStore.objectKey(componentVersionId), writeArchive(Map.of("index.mjs", "export async function handler() { return 'x'; }")));
        S3ArtifactStore store = new S3ArtifactStore("NODE", s3Client);

        store.resolve(componentId, componentVersionId);
        store.resolve(componentId, componentVersionId);

        // No caching or single-flight de-duplication belongs to this store;
        // two calls mean two independent downloads.
        assertEquals(2, s3Client.downloadCount());
    }

    private Path writeArchive(Map<String, String> entries) throws IOException {
        Path archive = Files.createTempFile(tempDir, "artifact-", ".tar.gz");
        try (
                var output = Files.newOutputStream(archive);
                var gzipOutput = new GZIPOutputStream(output);
                var tarOutput = new TarArchiveOutputStream(gzipOutput)
        ) {
            tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                byte[] content = entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                TarArchiveEntry tarEntry = new TarArchiveEntry(entry.getKey());
                tarEntry.setSize(content.length);
                tarOutput.putArchiveEntry(tarEntry);
                tarOutput.write(content);
                tarOutput.closeArchiveEntry();
            }
            tarOutput.finish();
        }
        return archive;
    }

    private static final class RecordingS3ArtifactClient implements S3ArtifactClient {
        private final Map<String, Path> objectsByKey = new ConcurrentHashMap<>();
        private final AtomicInteger downloadCount = new AtomicInteger();
        private final AtomicReference<String> lastKey = new AtomicReference<>();

        private void put(String key, Path source) {
            objectsByKey.put(key, source);
        }

        @Override
        public boolean download(String key, Path destination) {
            downloadCount.incrementAndGet();
            lastKey.set(key);
            Path source = objectsByKey.get(key);
            if (source == null) {
                return false;
            }
            try {
                Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to copy fake S3 object", exception);
            }
            return true;
        }

        @Override
        public void upload(String key, Path source) {
            throw new UnsupportedOperationException("S3ArtifactStoreTest only supports downloads");
        }

        private int downloadCount() {
            return downloadCount.get();
        }

        private String lastKey() {
            return lastKey.get();
        }
    }
}
