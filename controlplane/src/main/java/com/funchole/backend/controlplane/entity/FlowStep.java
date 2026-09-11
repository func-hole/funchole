package com.funchole.backend.controlplane.entity;

import com.funchole.backend.controlplane.constant.FlowStepComponentType;
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
@Table(name = "flow_steps")
public class FlowStep {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flow_version_id", nullable = false)
    private FlowVersion flowVersion;

    @Column(name = "step_key", nullable = false, length = 150)
    private String stepKey;

    @Column(name = "component_type", nullable = false, length = 100)
    @Enumerated(EnumType.STRING)
    private FlowStepComponentType componentType;

    @Column(nullable = false)
    private int position;

    @Column(name = "component_id", nullable = false)
    private UUID componentId;

    @Column(name = "component_version_id", nullable = false)
    private UUID componentVersionId;

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

    public FlowVersion getFlowVersion() {
        return flowVersion;
    }

    public String getStepKey() {
        return stepKey;
    }

    public FlowStepComponentType getComponentType() {
        return componentType;
    }

    public int getPosition() {
        return position;
    }

    public UUID getComponentId() {
        return componentId;
    }

    public UUID getComponentVersionId() {
        return componentVersionId;
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

    public void update(
            String stepKey,
            FlowStepComponentType componentType,
            int position,
            UUID componentId,
            UUID componentVersionId,
            String metadata
    ) {
        this.stepKey = stepKey;
        this.componentType = componentType;
        this.position = position;
        this.componentId = componentId;
        this.componentVersionId = componentVersionId;
        this.metadata = metadata;
        this.updatedAt = OffsetDateTime.now();
    }

    public static FlowStep create(
            FlowVersion flowVersion,
            String stepKey,
            FlowStepComponentType componentType,
            int position,
            UUID componentId,
            UUID componentVersionId,
            String metadata
    ) {
        FlowStep flowStep = new FlowStep();
        OffsetDateTime now = OffsetDateTime.now();
        flowStep.id = UUID.randomUUID();
        flowStep.flowVersion = flowVersion;
        flowStep.stepKey = stepKey;
        flowStep.componentType = componentType;
        flowStep.position = position;
        flowStep.componentId = componentId;
        flowStep.componentVersionId = componentVersionId;
        flowStep.metadata = metadata;
        flowStep.createdAt = now;
        flowStep.updatedAt = now;
        return flowStep;
    }
}
