package com.funchole.backend.runtime;

import com.funchole.backend.artifact.ArtifactReference;
import com.funchole.backend.artifact.ArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Runtime-local cache orchestration in front of a remote {@link ArtifactStore}
 * (e.g. {@code S3ArtifactStore}):
 *
 * <pre>
 * cache.resolve(...)
 *   -&gt; HIT:  return the local ArtifactReference
 *   -&gt; MISS: resolve from the remote store, materialize into the cache,
 *            return the now-local ArtifactReference
 * </pre>
 *
 * Same-process single-flight: concurrent misses for the exact same
 * componentVersionId share one remote resolution instead of each racing to
 * fetch and materialize independently. This coordination is a runtime
 * execution concern - the remote {@link ArtifactStore} implementation
 * itself has no notion of caching or deduplication.
 */
public final class CachedArtifactStore implements ArtifactStore {

    private final ArtifactCache cache;
    private final ArtifactStore remoteStore;
    private final ConcurrentMap<UUID, CompletableFuture<Optional<ArtifactReference>>> inFlightResolutions =
            new ConcurrentHashMap<>();

    public CachedArtifactStore(ArtifactCache cache, ArtifactStore remoteStore) {
        this.cache = cache;
        this.remoteStore = remoteStore;
    }

    @Override
    public Optional<ArtifactReference> resolve(UUID componentId, UUID componentVersionId) {
        if (componentId == null || componentVersionId == null) {
            return Optional.empty();
        }

        Optional<ArtifactReference> hit = cache.resolve(componentId, componentVersionId);
        if (hit.isPresent()) {
            return hit;
        }

        CompletableFuture<Optional<ArtifactReference>> resolver = new CompletableFuture<>();
        CompletableFuture<Optional<ArtifactReference>> inFlight = inFlightResolutions.putIfAbsent(
                componentVersionId, resolver);
        if (inFlight != null) {
            return await(inFlight);
        }

        try {
            Optional<ArtifactReference> racedHit = cache.resolve(componentId, componentVersionId);
            Optional<ArtifactReference> resolved = racedHit.isPresent()
                    ? racedHit
                    : resolveCold(componentId, componentVersionId);
            resolver.complete(resolved);
            return resolved;
        } catch (RuntimeException exception) {
            resolver.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlightResolutions.remove(componentVersionId, resolver);
        }
    }

    private Optional<ArtifactReference> await(CompletableFuture<Optional<ArtifactReference>> inFlight) {
        try {
            return inFlight.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private Optional<ArtifactReference> resolveCold(UUID componentId, UUID componentVersionId) {
        Optional<ArtifactReference> remote = remoteStore.resolve(componentId, componentVersionId);
        if (remote.isEmpty()) {
            return Optional.empty();
        }

        // The remote store hands us a temporary directory it owns until we
        // consume it; materialize it into the stable cache, then remove it.
        Path remoteExtractedDirectory = remote.get().artifactPath().getParent();
        try {
            return Optional.of(cache.put(componentId, componentVersionId, remoteExtractedDirectory));
        } finally {
            deleteRecursively(remoteExtractedDirectory);
        }
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
                    // Remote artifact workspace cleanup is best-effort.
                }
            });
        } catch (IOException ignored) {
            // Remote artifact workspace cleanup is best-effort.
        }
    }
}
