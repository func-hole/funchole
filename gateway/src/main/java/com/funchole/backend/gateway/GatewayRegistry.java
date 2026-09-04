package com.funchole.backend.gateway;

import io.netty.handler.ssl.SslContext;
import io.netty.util.Mapping;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class GatewayRegistry {
    private final AtomicReference<GatewayRegistrySnapshot> snapshot;

    public GatewayRegistry(GatewayRegistrySnapshot initialSnapshot) {
        this.snapshot = new AtomicReference<>(initialSnapshot);
    }

    public GatewayRuntimeEntry findByHostname(String hostname) {
        return currentSnapshot().entriesByHostname().get(normalizeHostname(hostname));
    }

    public Collection<GatewayRuntimeEntry> entries() {
        return List.copyOf(currentSnapshot().entriesByHostname().values());
    }

    public int size() {
        return currentSnapshot().entriesByHostname().size();
    }

    public SslContext resolveSslContext(String hostname) {
        GatewayRuntimeEntry entry = findByHostname(hostname);
        if (entry != null) {
            return entry.sslContext();
        }
        return currentSnapshot().defaultSslContext();
    }

    public void replace(GatewayRegistrySnapshot nextSnapshot) {
        snapshot.set(nextSnapshot);
    }

    public Mapping<String, SslContext> sslContextMapping() {
        return this::resolveSslContext;
    }

    private GatewayRegistrySnapshot currentSnapshot() {
        return snapshot.get();
    }

    private String normalizeHostname(String hostname) {
        return hostname == null ? "" : hostname.trim().toLowerCase();
    }
}
