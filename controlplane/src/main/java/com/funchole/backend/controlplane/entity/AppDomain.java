package com.funchole.backend.controlplane.entity;

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

import com.funchole.backend.controlplane.constant.DomainStatus;

@Entity
@Table(name = "app_domains")
public class AppDomain {

    @Id
    private UUID id;

    @Column(name = "app_user_id", nullable = false)
    private UUID appUserId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @Column(name = "domain_name", nullable = false, length = 255)
    private String domainName;

    @Column(name = "verification_code", nullable = false, length = 255)
    private String verificationCode;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DomainStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public UUID getAppUserId() {
        return appUserId;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public String getDomainName() {
        return domainName;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public DomainStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
