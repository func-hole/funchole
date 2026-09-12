package com.funchole.backend.controlplane.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Build input for an exact FunctionVersion, kept as a sibling of the
 * FunctionVersion's artifact fields rather than merged into them: source is
 * what a client submits before a build; the artifact is what a build later
 * produces. The primary key is the owning FunctionVersion's id directly (no
 * separate surrogate id), since exactly one source bundle exists per version.
 *
 * <p>Only the manifest (relative paths) lives here - file content lives
 * behind {@link com.funchole.backend.controlplane.service.SourceStore}, not
 * in Postgres.
 */
@Entity
@Table(name = "function_version_sources")
public class FunctionVersionSource {

    @Id
    @Column(name = "function_version_id")
    private UUID functionVersionId;

    @Column(name = "runtime_type", nullable = false, length = 100)
    private String runtimeType;

    @Column(name = "runtime_version", length = 100)
    private String runtimeVersion;

    @Column(nullable = false, length = 2048)
    private String entrypoint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "relative_paths", nullable = false, columnDefinition = "jsonb")
    private String relativePaths;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getFunctionVersionId() {
        return functionVersionId;
    }

    public String getRuntimeType() {
        return runtimeType;
    }

    public String getRuntimeVersion() {
        return runtimeVersion;
    }

    public String getEntrypoint() {
        return entrypoint;
    }

    public String getRelativePaths() {
        return relativePaths;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void replaceWith(String runtimeType, String runtimeVersion, String entrypoint, String relativePaths) {
        this.runtimeType = runtimeType;
        this.runtimeVersion = runtimeVersion;
        this.entrypoint = entrypoint;
        this.relativePaths = relativePaths;
        this.updatedAt = OffsetDateTime.now();
    }

    public static FunctionVersionSource create(
            UUID functionVersionId,
            String runtimeType,
            String runtimeVersion,
            String entrypoint,
            String relativePaths
    ) {
        FunctionVersionSource source = new FunctionVersionSource();
        OffsetDateTime now = OffsetDateTime.now();
        source.functionVersionId = functionVersionId;
        source.runtimeType = runtimeType;
        source.runtimeVersion = runtimeVersion;
        source.entrypoint = entrypoint;
        source.relativePaths = relativePaths;
        source.createdAt = now;
        source.updatedAt = now;
        return source;
    }
}
