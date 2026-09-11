package com.funchole.backend.controlplane.entity;

import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_versions")
public class FlowVersion {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flow_id", nullable = false)
    private Flow flow;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 100)
    @Enumerated(EnumType.STRING)
    private FlowVersionStatus status;

    @Column(nullable = false, length = 100)
    private String runtime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "adopted_at")
    private OffsetDateTime adoptedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    public UUID getId() {
        return id;
    }

    public Flow getFlow() {
        return flow;
    }

    public int getVersion() {
        return version;
    }

    public FlowVersionStatus getStatus() {
        return status;
    }

    public String getRuntime() {
        return runtime;
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

    public OffsetDateTime getAdoptedAt() {
        return adoptedAt;
    }

    public OffsetDateTime getArchivedAt() {
        return archivedAt;
    }

    public void adopt() {
        this.status = FlowVersionStatus.ADOPTED;
        this.adoptedAt = OffsetDateTime.now();
        this.updatedAt = this.adoptedAt;
    }

    public void archive() {
        this.status = FlowVersionStatus.ARCHIVED;
        this.archivedAt = OffsetDateTime.now();
        this.updatedAt = this.archivedAt;
    }

    public static FlowVersion create(Flow flow, int version, String runtime, String metadata) {
        FlowVersion flowVersion = new FlowVersion();
        OffsetDateTime now = OffsetDateTime.now();
        flowVersion.id = UUID.randomUUID();
        flowVersion.flow = flow;
        flowVersion.version = version;
        flowVersion.status = FlowVersionStatus.DRAFT;
        flowVersion.runtime = runtime;
        flowVersion.metadata = metadata;
        flowVersion.createdAt = now;
        flowVersion.updatedAt = now;
        return flowVersion;
    }
}
