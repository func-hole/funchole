package com.funchole.backend.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal test-only {@link ArtifactCache}: no atomic-move/staging hardening
 * (that behavior belongs to the runtime module's FilesystemArtifactCache and
 * is covered by its own tests). This exists only so artifact-module tests
 * can exercise {@link S3ArtifactStore}'s cache-hit/cache-miss/single-flight
 * behavior without depending on the runtime module.
 */
final class InMemoryArtifactCache implements ArtifactCache {

    private static final String ENTRY_POINT_FILE_NAME = "index.mjs";

    private final Path cacheRoot;
    private final String runtimeType;
    private final Map<UUID, Path> entriesByComponentVersionId = new ConcurrentHashMap<>();

    InMemoryArtifactCache(Path cacheRoot, String runtimeType) {
        this.cacheRoot = cacheRoot;
        this.runtimeType = runtimeType;
    }

    @Override
    public Optional<ArtifactReference> resolve(UUID componentId, UUID componentVersionId) {
        Path artifactPath = entriesByComponentVersionId.get(componentVersionId);
        if (artifactPath == null || !Files.isRegularFile(artifactPath)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactReference(componentId, componentVersionId, runtimeType, artifactPath));
    }

    @Override
    public synchronized ArtifactReference put(UUID componentId, UUID componentVersionId, Path sourceArtifactDirectory) {
        Optional<ArtifactReference> existing = resolve(componentId, componentVersionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Path entryDirectory = cacheRoot.resolve(componentVersionId.toString());
        try {
            Files.createDirectories(entryDirectory);
            try (var files = Files.walk(sourceArtifactDirectory)) {
                files.filter(Files::isRegularFile).forEach(source -> copy(sourceArtifactDirectory, source, entryDirectory));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to cache artifact for componentVersionId=" + componentVersionId, exception);
        }
        entriesByComponentVersionId.put(componentVersionId, entryDirectory.resolve(ENTRY_POINT_FILE_NAME));
        return resolve(componentId, componentVersionId).orElseThrow();
    }

    private void copy(Path sourceArtifactDirectory, Path source, Path entryDirectory) {
        Path relative = sourceArtifactDirectory.relativize(source);
        Path target = entryDirectory.resolve(relative);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to copy artifact source into cache: " + source, exception);
        }
    }
}
