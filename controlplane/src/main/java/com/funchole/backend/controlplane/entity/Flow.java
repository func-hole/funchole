package com.funchole.backend.controlplane.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "flows")
public class Flow {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gateway_id", nullable = false)
    private Gateway gateway;

    @Column(name = "active_flow_version_id")
    private UUID activeFlowVersionId;

    @Column(name = "active_flow_version_status", length = 100)
    private String activeFlowVersionStatus;

    @Column(name = "flow_key", nullable = false, length = 150, unique = true)
    private String flowKey;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "http_method", nullable = false, length = 50)
    private String httpMethod;

    @Column(nullable = false, length = 2048)
    private String path;

    @Column(nullable = false)
    private int priority;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public UUID getId() {
        return id;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public UUID getActiveFlowVersionId() {
        return activeFlowVersionId;
    }

    public String getActiveFlowVersionStatus() {
        return activeFlowVersionStatus;
    }

    public String getFlowKey() {
        return flowKey;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getPath() {
        return path;
    }

    public int getPriority() {
        return priority;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void update(Gateway gateway, String name, String description, String httpMethod, String path, int priority) {
        this.gateway = gateway;
        this.name = name;
        this.description = description;
        this.httpMethod = httpMethod;
        this.path = path;
        this.priority = priority;
        this.updatedAt = OffsetDateTime.now();
    }

    public void activateVersion(UUID flowVersionId, String status) {
        this.activeFlowVersionId = flowVersionId;
        this.activeFlowVersionStatus = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public void clearActiveVersion(UUID flowVersionId) {
        if (flowVersionId.equals(this.activeFlowVersionId)) {
            this.activeFlowVersionId = null;
            this.activeFlowVersionStatus = null;
            this.updatedAt = OffsetDateTime.now();
        }
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public static Flow create(
            AppUser appUser,
            Gateway gateway,
            String flowKey,
            String name,
            String description,
            String httpMethod,
            String path,
            int priority
    ) {
        Flow flow = new Flow();
        OffsetDateTime now = OffsetDateTime.now();
        flow.id = UUID.randomUUID();
        flow.appUser = appUser;
        flow.gateway = gateway;
        flow.flowKey = flowKey;
        flow.name = name;
        flow.description = description;
        flow.httpMethod = httpMethod;
        flow.path = path;
        flow.priority = priority;
        flow.createdAt = now;
        flow.updatedAt = now;
        return flow;
    }
}
