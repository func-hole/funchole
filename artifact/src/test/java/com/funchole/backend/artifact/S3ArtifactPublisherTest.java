package com.funchole.backend.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3ArtifactPublisherTest {

    @TempDir
    Path tempDir;

    @Test
    void nestedArtifactDirectoryIsPackagedAndUploaded() throws Exception {
        UUID componentVersionId = UUID.randomUUID();
        Path preparedArtifact = writePreparedArtifact(componentVersionId);
        RecordingUploadS3ArtifactClient s3Client = new RecordingUploadS3ArtifactClient(tempDir.resolve("remote"));
        S3ArtifactPublisher publisher = new S3ArtifactPublisher(s3Client);

        PublishedArtifact published = publisher.publish(componentVersionId, preparedArtifact);

        assertEquals(componentVersionId, published.componentVersionId());
        assertEquals(S3ArtifactStore.objectKey(componentVersionId), published.objectKey());

        Path extracted = tempDir.resolve("extracted");
        ArtifactArchiveExtractor.extractTarGz(s3Client.uploadedArchive(published.objectKey()), extracted);
        assertTrue(Files.isRegularFile(extracted.resolve("index.mjs")));
        assertTrue(Files.isRegularFile(extracted.resolve("lib/client.mjs")));
        assertTrue(Files.isRegularFile(extracted.resolve("config/settings.json")));
        assertEquals("export const client = 'client';", Files.readString(extracted.resolve("lib/client.mjs")));
    }

    @Test
    void checksumAndSizeReflectExactUploadedArchiveBytes() throws Exception {
        UUID componentVersionId = UUID.randomUUID();
        Path preparedArtifact = writePreparedArtifact(componentVersionId);
        RecordingUploadS3ArtifactClient s3Client = new RecordingUploadS3ArtifactClient(tempDir.resolve("remote"));
        S3ArtifactPublisher publisher = new S3ArtifactPublisher(s3Client);

        PublishedArtifact published = publisher.publish(componentVersionId, preparedArtifact);

        Path uploadedArchive = s3Client.uploadedArchive(published.objectKey());
        assertEquals(Files.size(uploadedArchive), published.sizeBytes());
        assertEquals(sha256Hex(uploadedArchive), published.sha256());
        assertEquals(64, published.sha256().length());
    }

    @Test
    void exactComponentVersionProducesExpectedObjectKey() throws Exception {
        UUID componentVersionId = UUID.randomUUID();
        RecordingUploadS3ArtifactClient s3Client = new RecordingUploadS3ArtifactClient(tempDir.resolve("remote"));
        S3ArtifactPublisher publisher = new S3ArtifactPublisher(s3Client);

        PublishedArtifact published = publisher.publish(componentVersionId, writePreparedArtifact(componentVersionId));

        assertEquals("artifacts/" + componentVersionId + "/artifact.tar.gz", published.objectKey());
        assertEquals(published.objectKey(), s3Client.lastUploadedKey());
    }

    @Test
    void uploadedArchiveCanBeResolvedByExistingS3ArtifactStore() throws Exception {
        UUID componentId = UUID.randomUUID();
        UUID componentVersionId = UUID.randomUUID();
        RecordingUploadS3ArtifactClient s3Client = new RecordingUploadS3ArtifactClient(tempDir.resolve("remote"));
        S3ArtifactPublisher publisher = new S3ArtifactPublisher(s3Client);
        publisher.publish(componentVersionId, writePreparedArtifact(componentVersionId));
        S3ArtifactStore store = new S3ArtifactStore(
                new InMemoryArtifactCache(tempDir.resolve("cache"), "NODE"),
                s3Client
        );

        ArtifactReference reference = store.resolve(componentId, componentVersionId).orElseThrow();

        assertTrue(Files.isRegularFile(reference.artifactPath()));
        assertTrue(Files.isRegularFile(reference.artifactPath().getParent().resolve("lib/client.mjs")));
    }

    @Test
    void differentVersionsUploadToDifferentKeys() throws Exception {
        UUID versionA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        RecordingUploadS3ArtifactClient s3Client = new RecordingUploadS3ArtifactClient(tempDir.resolve("remote"));
        S3ArtifactPublisher publisher = new S3ArtifactPublisher(s3Client);

        PublishedArtifact first = publisher.publish(versionA, writePreparedArtifact(versionA));
        PublishedArtifact second = publisher.publish(versionB, writePreparedArtifact(versionB));

        assertTrue(first.objectKey().contains(versionA.toString()));
        assertTrue(second.objectKey().contains(versionB.toString()));
        assertTrue(s3Client.hasUploaded(first.objectKey()));
        assertTrue(s3Client.hasUploaded(second.objectKey()));
        assertEquals(2, s3Client.uploadCount());
    }

    @Test
    void uploadFailureIsSurfacedClearly() throws Exception {
        UUID componentVersionId = UUID.randomUUID();
        RecordingUploadS3ArtifactClient s3Client = new RecordingUploadS3ArtifactClient(tempDir.resolve("remote"));
        s3Client.failUploads();
        S3ArtifactPublisher publisher = new S3ArtifactPublisher(s3Client);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> publisher.publish(componentVersionId, writePreparedArtifact(componentVersionId))
        );

        assertTrue(exception.getMessage().contains(S3ArtifactStore.objectKey(componentVersionId)));
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path writePreparedArtifact(UUID componentVersionId) throws IOException {
        Path directory = tempDir.resolve("prepared").resolve(componentVersionId.toString());
        Files.createDirectories(directory.resolve("lib"));
        Files.createDirectories(directory.resolve("config"));
        Files.writeString(directory.resolve("index.mjs"),
                "import { client } from './lib/client.mjs'; export async function handler() { return client; }");
        Files.writeString(directory.resolve("lib/client.mjs"), "export const client = 'client';");
        Files.writeString(directory.resolve("config/settings.json"), "{\"enabled\":true}");
        return directory;
    }

    private static final class RecordingUploadS3ArtifactClient implements S3ArtifactClient {
        private final Path remoteRoot;
        private final Map<String, Path> uploadsByKey = new ConcurrentHashMap<>();
        private String lastUploadedKey;
        private boolean failUploads;

        private RecordingUploadS3ArtifactClient(Path remoteRoot) {
            this.remoteRoot = remoteRoot;
        }

        @Override
        public boolean download(String key, Path destination) {
            Path source = uploadsByKey.get(key);
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
            lastUploadedKey = key;
            if (failUploads) {
                throw new IllegalStateException("Failed to upload artifact to S3 key " + key);
            }
            try {
                Path destination = remoteRoot.resolve(key.replace('/', '_'));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                uploadsByKey.put(key, destination);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to store fake S3 object", exception);
            }
        }

        private Path uploadedArchive(String key) {
            return uploadsByKey.get(key);
        }

        private String lastUploadedKey() {
            return lastUploadedKey;
        }

        private boolean hasUploaded(String key) {
            return uploadsByKey.containsKey(key);
        }

        private int uploadCount() {
            return uploadsByKey.size();
        }

        private void failUploads() {
            failUploads = true;
        }
    }
}
