package com.funchole.backend.runtime;

import com.funchole.backend.artifact.ArtifactCache;
import com.funchole.backend.artifact.ArtifactReference;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final Runnable beforePublish;

    public FilesystemArtifactCache(Path cacheRoot, String runtimeType) {
        this(cacheRoot, runtimeType, () -> {
        });
    }

    FilesystemArtifactCache(Path cacheRoot, String runtimeType, Runnable beforePublish) {
        this.cacheRoot = cacheRoot;
        this.runtimeType = runtimeType;
        this.beforePublish = beforePublish;
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
        Path stagingDirectory = stagingEntry(componentVersionId);
        try {
            Files.createDirectories(cacheRoot);
            deleteRecursively(stagingDirectory);
            Files.createDirectories(stagingDirectory);
            try (var files = Files.walk(sourceArtifactDirectory)) {
                files.filter(Files::isRegularFile).forEach(source -> copy(sourceArtifactDirectory, source, stagingDirectory));
            }
            beforePublish.run();

            Optional<ArtifactReference> raced = resolve(componentId, componentVersionId);
            if (raced.isPresent()) {
                deleteRecursively(stagingDirectory);
                return raced.get();
            }

            try {
                publish(stagingDirectory, entryDirectory);
            } catch (FileAlreadyExistsException exception) {
                Optional<ArtifactReference> racedAfterMove = resolve(componentId, componentVersionId);
                if (racedAfterMove.isPresent()) {
                    return racedAfterMove.get();
                }
                throw exception;
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to cache artifact for componentVersionId=" + componentVersionId, exception);
        } finally {
            deleteRecursively(stagingDirectory);
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

    private void publish(Path stagingDirectory, Path entryDirectory) throws IOException {
        try {
            Files.move(stagingDirectory, entryDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IllegalStateException("Atomic artifact cache publication is not supported by this filesystem", exception);
        }
    }

    private Path cacheEntry(UUID componentVersionId) {
        return cacheRoot.resolve(componentVersionId.toString());
    }

    private Path stagingEntry(UUID componentVersionId) {
        return cacheRoot.resolve("." + componentVersionId + "." + UUID.randomUUID() + ".staging");
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Staging cleanup is best-effort.
                }
            });
        } catch (IOException ignored) {
            // Staging cleanup is best-effort.
        }
    }
}
