package com.funchole.backend.runtime;

import com.funchole.backend.artifact.ArtifactReference;
import com.funchole.backend.artifact.ArtifactStore;
import com.funchole.backend.artifact.RemoteArtifact;
import com.funchole.backend.artifact.RemoteArtifactStore;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Runtime-local cache orchestration in front of a remote
 * {@link RemoteArtifactStore} (e.g. {@code S3ArtifactStore}):
 *
 * <pre>
 * cache.resolve(...)
 *   -&gt; HIT:  return the local ArtifactReference
 *   -&gt; MISS: resolve from the remote store, materialize into the cache,
 *            close the temporary remote artifact, return the now-local
 *            ArtifactReference
 * </pre>
 *
 * The temporary remote artifact is only ever opened on a miss and is always
 * closed via try-with-resources, so it is cleaned up whether materialization
 * succeeds, fails, or {@code cache.put} throws.
 *
 * Same-process single-flight: concurrent misses for the exact same
 * componentVersionId share one remote resolution instead of each racing to
 * fetch and materialize independently. This coordination is a runtime
 * execution concern - the remote store implementation itself has no notion
 * of caching or deduplication.
 */
public final class CachedArtifactStore implements ArtifactStore {

    private final ArtifactCache cache;
    private final RemoteArtifactStore remoteStore;
    private final ConcurrentMap<UUID, CompletableFuture<Optional<ArtifactReference>>> inFlightResolutions =
            new ConcurrentHashMap<>();

    public CachedArtifactStore(ArtifactCache cache, RemoteArtifactStore remoteStore) {
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
        Optional<RemoteArtifact> remote = remoteStore.resolve(componentId, componentVersionId);
        if (remote.isEmpty()) {
            return Optional.empty();
        }

        // try-with-resources guarantees the temporary remote artifact is
        // removed even if cache.put(...) throws.
        try (RemoteArtifact remoteArtifact = remote.get()) {
            Path extractedDirectory = remoteArtifact.reference().artifactPath().getParent();
            return Optional.of(cache.put(componentId, componentVersionId, extractedDirectory));
        }
    }
}
