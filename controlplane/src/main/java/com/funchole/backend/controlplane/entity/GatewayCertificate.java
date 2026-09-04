package com.funchole.backend.controlplane.entity;

import com.funchole.backend.certificate.CertificateProvider;
import com.funchole.backend.certificate.CertificateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "certificates")
public class GatewayCertificate {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gateway_id", nullable = false, unique = true)
    private Gateway gateway;

    @Column(nullable = false, length = 255)
    private String hostname;

    @Column(name = "wildcard_hostname", length = 255)
    private String wildcardHostname;

    @Column(nullable = false, length = 100)
    @Enumerated(EnumType.STRING)
    private CertificateProvider provider;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private CertificateStatus status;

    @Column(name = "secret_ref", length = 255)
    private String secretRef;

    @Column(name = "issued_at")
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "renewed_at")
    private OffsetDateTime renewedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public String getHostname() {
        return hostname;
    }

    public String getWildcardHostname() {
        return wildcardHostname;
    }

    public CertificateProvider getProvider() {
        return provider;
    }

    public CertificateStatus getStatus() {
        return status;
    }

    public String getSecretRef() {
        return secretRef;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getRenewedAt() {
        return renewedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void markActive(OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        this.status = CertificateStatus.ACTIVE;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.renewedAt = issuedAt;
        this.updatedAt = OffsetDateTime.now();
    }

    public void markFailed() {
        this.status = CertificateStatus.FAILED;
        this.updatedAt = OffsetDateTime.now();
    }

    public void updateProvisioningTarget(
            String hostname,
            String wildcardHostname,
            CertificateProvider provider,
            String secretRef
    ) {
        this.hostname = hostname;
        this.wildcardHostname = wildcardHostname;
        this.provider = provider;
        this.secretRef = secretRef;
        this.status = CertificateStatus.PENDING;
        this.issuedAt = null;
        this.expiresAt = null;
        this.updatedAt = OffsetDateTime.now();
    }

    public static GatewayCertificate create(
            Gateway gateway,
            String hostname,
            String wildcardHostname,
            CertificateProvider provider,
            String secretRef
    ) {
        GatewayCertificate certificate = new GatewayCertificate();
        OffsetDateTime now = OffsetDateTime.now();
        certificate.id = UUID.randomUUID();
        certificate.gateway = gateway;
        certificate.hostname = hostname;
        certificate.wildcardHostname = wildcardHostname;
        certificate.provider = provider;
        certificate.status = CertificateStatus.PENDING;
        certificate.secretRef = secretRef;
        certificate.createdAt = now;
        certificate.updatedAt = now;
        return certificate;
    }
}
