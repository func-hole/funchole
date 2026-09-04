package com.funchole.backend.controlplane.entity;

import com.funchole.backend.controlplane.constant.GatewayStatus;
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

@Entity
@Table(name = "gateways")
public class Gateway {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_domain_id", nullable = false)
    private AppDomain appDomain;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "unique_key", nullable = false, length = 64, unique = true)
    private String uniqueKey;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private GatewayStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public AppDomain getAppDomain() {
        return appDomain;
    }

    public String getName() {
        return name;
    }

    public String getUniqueKey() {
        return uniqueKey;
    }

    public String getDescription() {
        return description;
    }

    public GatewayStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void update(AppDomain appDomain, String name, String uniqueKey, String description, GatewayStatus status) {
        this.appDomain = appDomain;
        this.name = name;
        this.uniqueKey = uniqueKey;
        this.description = description;
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public static Gateway create(
            AppUser appUser,
            AppDomain appDomain,
            String name,
            String uniqueKey,
            String description,
            GatewayStatus status
    ) {
        Gateway gateway = new Gateway();
        OffsetDateTime now = OffsetDateTime.now();
        gateway.id = UUID.randomUUID();
        gateway.appUser = appUser;
        gateway.appDomain = appDomain;
        gateway.name = name;
        gateway.uniqueKey = uniqueKey;
        gateway.description = description;
        gateway.status = status;
        gateway.createdAt = now;
        gateway.updatedAt = now;
        return gateway;
    }
}
