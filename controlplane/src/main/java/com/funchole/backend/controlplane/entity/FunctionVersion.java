package com.funchole.backend.controlplane.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "function_versions")
public class FunctionVersion {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "function_id", nullable = false)
    private Function function;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 100)
    private String runtime;

    @Column(name = "artifact_object_key", length = 2048)
    private String artifactObjectKey;

    @Column(name = "artifact_format", length = 100)
    private String artifactFormat;

    @Column(name = "artifact_sha256", length = 64)
    private String artifactSha256;

    @Column(name = "artifact_size_bytes")
    private Long artifactSizeBytes;

    @Column(name = "artifact_published_at")
    private OffsetDateTime artifactPublishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public Function getFunction() {
        return function;
    }

    public int getVersion() {
        return version;
    }

    public String getRuntime() {
        return runtime;
    }

    public Optional<ArtifactMetadata> getArtifactMetadata() {
        if (artifactObjectKey == null || artifactFormat == null || artifactSha256 == null || artifactSizeBytes == null) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactMetadata(artifactObjectKey, artifactFormat, artifactSha256, artifactSizeBytes, artifactPublishedAt));
    }

    public String getArtifactObjectKey() {
        return artifactObjectKey;
    }

    public String getArtifactFormat() {
        return artifactFormat;
    }

    public String getArtifactSha256() {
        return artifactSha256;
    }

    public Long getArtifactSizeBytes() {
        return artifactSizeBytes;
    }

    public OffsetDateTime getArtifactPublishedAt() {
        return artifactPublishedAt;
    }

    public String getMetadata() {
        return metadata;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void attachArtifact(String artifactObjectKey, String artifactFormat, String artifactSha256, long artifactSizeBytes) {
        this.artifactObjectKey = artifactObjectKey;
        this.artifactFormat = artifactFormat;
        this.artifactSha256 = artifactSha256;
        this.artifactSizeBytes = artifactSizeBytes;
        this.artifactPublishedAt = OffsetDateTime.now();
        this.updatedAt = this.artifactPublishedAt;
    }

    public static FunctionVersion create(Function function, int version, String runtime, String metadata) {
        FunctionVersion functionVersion = new FunctionVersion();
        OffsetDateTime now = OffsetDateTime.now();
        functionVersion.id = UUID.randomUUID();
        functionVersion.function = function;
        functionVersion.version = version;
        functionVersion.runtime = runtime;
        functionVersion.metadata = metadata;
        functionVersion.createdAt = now;
        functionVersion.updatedAt = now;
        return functionVersion;
    }
}
