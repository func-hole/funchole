package com.funchole.backend.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3ArtifactStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void cacheHitDoesNotAccessS3() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        FilesystemArtifactCache cache = new FilesystemArtifactCache(tempDir.resolve("cache"), "NODE");
        Path source = writeArtifactDirectory(componentVersionId, "export async function handler() { return 'hit'; }");
        cache.put(componentId, componentVersionId, source);
        RecordingS3ArtifactClient s3Client = new RecordingS3ArtifactClient();
        S3ArtifactStore store = new S3ArtifactStore(cache, s3Client);

        Optional<ArtifactReference> resolved = store.resolve(componentId, componentVersionId);

        assertTrue(resolved.isPresent());
        assertEquals(0, s3Client.downloadCount());
        assertEquals(cache.resolve(componentId, componentVersionId).orElseThrow().artifactPath(), resolved.get().artifactPath());
    }

    @Test
    void cacheMissDownloadsExactPinnedComponentVersion() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        Path archive = writeArchive(Map.of("index.mjs", "export async function handler() { return 'remote'; }"));
        RecordingS3ArtifactClient s3Client = new RecordingS3ArtifactClient();
        s3Client.put(S3ArtifactStore.objectKey(componentVersionId), archive);
        FilesystemArtifactCache cache = new FilesystemArtifactCache(tempDir.resolve("cache"), "NODE");
        S3ArtifactStore store = new S3ArtifactStore(cache, s3Client);

        Optional<ArtifactReference> resolved = store.resolve(componentId, componentVersionId);

        assertTrue(resolved.isPresent());
        assertEquals(1, s3Client.downloadCount());
        assertEquals("artifacts/" + componentVersionId + "/artifact.tar.gz", s3Client.lastKey());
        assertTrue(Files.isRegularFile(tempDir.resolve("cache").resolve(componentVersionId.toString()).resolve("index.mjs")));
    }

    @Test
    void downloadedArchiveIsMaterializedIntoCacheAndPreservesNestedDirectories() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        Path archive = writeArchive(Map.of(
                "index.mjs", "import { value } from './lib/value.mjs'; export async function handler() { return value; }",
                "lib/value.mjs", "export const value = 42;",
                "config/settings.json", "{\"enabled\":true}"
        ));
        RecordingS3ArtifactClient s3Client = new RecordingS3ArtifactClient();
        s3Client.put(S3ArtifactStore.objectKey(componentVersionId), archive);
        Path cacheRoot = tempDir.resolve("cache");
        S3ArtifactStore store = new S3ArtifactStore(new FilesystemArtifactCache(cacheRoot, "NODE"), s3Client);

        ArtifactReference resolved = store.resolve(componentId, componentVersionId).orElseThrow();

        Path cacheEntry = cacheRoot.resolve(componentVersionId.toString());
        assertEquals(cacheEntry.resolve("index.mjs").toAbsolutePath(), resolved.artifactPath());
        assertTrue(Files.isRegularFile(cacheEntry.resolve("lib/value.mjs")));
        assertTrue(Files.isRegularFile(cacheEntry.resolve("config/settings.json")));
    }

    @Test
    void missingRemoteArtifactKeepsArtifactNotFoundBehavior() {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        RecordingS3ArtifactClient s3Client = new RecordingS3ArtifactClient();
        S3ArtifactStore store = new S3ArtifactStore(
                new FilesystemArtifactCache(tempDir.resolve("cache"), "NODE"),
                s3Client
        );

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
        Path cacheRoot = tempDir.resolve("cache");
        S3ArtifactStore store = new S3ArtifactStore(new FilesystemArtifactCache(cacheRoot, "NODE"), s3Client);

        ArtifactReference artifactA = store.resolve(componentId, versionA).orElseThrow();
        ArtifactReference artifactB = store.resolve(componentId, versionB).orElseThrow();

        assertTrue(artifactA.artifactPath().toString().contains(versionA.toString()));
        assertTrue(artifactB.artifactPath().toString().contains(versionB.toString()));
        assertTrue(Files.readString(artifactA.artifactPath()).contains("'A'"));
        assertTrue(Files.readString(artifactB.artifactPath()).contains("'B'"));
        assertEquals(2, s3Client.downloadCount());
    }

    private Path writeArtifactDirectory(UUID componentVersionId, String source) throws IOException {
        Path directory = tempDir.resolve("source").resolve(componentVersionId.toString());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("index.mjs"), source);
        return directory;
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
        private final Map<String, Path> objectsByKey = new HashMap<>();
        private final AtomicInteger downloadCount = new AtomicInteger();
        private String lastKey;

        private void put(String key, Path source) {
            objectsByKey.put(key, source);
        }

        @Override
        public boolean download(String key, Path destination) {
            downloadCount.incrementAndGet();
            lastKey = key;
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

        private int downloadCount() {
            return downloadCount.get();
        }

        private String lastKey() {
            return lastKey;
        }
    }
}
