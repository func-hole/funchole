package com.funchole.backend.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Filesystem-backed {@link ArtifactCache}.
 *
 * Layout: {@code <cacheRoot>/<componentVersionId>/index.mjs} - one directory
 * per exact component version, so versions never collide and there is no
 * version-scan behavior. No eviction, no TTL, no cross-process locking
 * (documented limitations; future remote-store milestones can add them).
 */
public final class FilesystemArtifactCache implements ArtifactCache {

    private static final String ENTRY_POINT_FILE_NAME = "index.mjs";

    private final Path cacheRoot;
    private final String runtimeType;

    public FilesystemArtifactCache(Path cacheRoot, String runtimeType) {
        this.cacheRoot = cacheRoot;
        this.runtimeType = runtimeType;
    }

    @Override
    public Optional<ArtifactReference> resolve(UUID componentId, UUID componentVersionId) {
        if (componentId == null || componentVersionId == null) {
            return Optional.empty();
        }
        Path artifactPath = cacheEntry(componentVersionId).resolve(ENTRY_POINT_FILE_NAME);
        if (!Files.isRegularFile(artifactPath)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactReference(componentId, componentVersionId, runtimeType, artifactPath.toAbsolutePath()));
    }

    @Override
    public ArtifactReference put(UUID componentId, UUID componentVersionId, Path sourceArtifactDirectory) {
        Optional<ArtifactReference> existing = resolve(componentId, componentVersionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Path entryDirectory = cacheEntry(componentVersionId);
        try {
            Files.createDirectories(entryDirectory);
            try (var files = Files.walk(sourceArtifactDirectory)) {
                files.filter(Files::isRegularFile).forEach(source -> copy(sourceArtifactDirectory, source, entryDirectory));
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to cache artifact for componentVersionId=" + componentVersionId, exception);
        }
        return resolve(componentId, componentVersionId).orElseThrow(() ->
                new IllegalStateException("Cached artifact did not resolve for componentVersionId=" + componentVersionId));
    }

    /**
     * Copies {@code source} into the cache entry preserving its path relative
     * to {@code sourceArtifactDirectory}, creating parent directories as
     * needed - nested artifact structures keep their shape and same-named
     * files in different directories never collide.
     */
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

    private Path cacheEntry(UUID componentVersionId) {
        return cacheRoot.resolve(componentVersionId.toString());
    }
}
