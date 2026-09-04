package com.funchole.backend.controlplane.service;

import com.funchole.backend.certificate.CertificateProvider;
import com.funchole.backend.certificate.CertificateReference;
import com.funchole.backend.certificate.CertificateRequest;
import com.funchole.backend.certificate.CertificateStatus;
import com.funchole.backend.certificate.GeneratedCertificate;
import com.funchole.backend.certificate.generator.CertificateGenerator;
import com.funchole.backend.certificate.store.CertificateStore;
import com.funchole.backend.controlplane.config.CertificateProperties;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.entity.GatewayCertificate;
import com.funchole.backend.controlplane.event.GatewayCertificateProvisionRequested;
import com.funchole.backend.controlplane.repository.GatewayCertificateRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class GatewayCertificateService {
    private static final Logger logger = LoggerFactory.getLogger(GatewayCertificateService.class);

    private final GatewayCertificateRepository gatewayCertificateRepository;
    private final CertificateGenerator certificateGenerator;
    private final CertificateStore certificateStore;
    private final CertificateProperties certificateProperties;

    public GatewayCertificateService(
            GatewayCertificateRepository gatewayCertificateRepository,
            CertificateGenerator certificateGenerator,
            CertificateStore certificateStore,
            CertificateProperties certificateProperties
    ) {
        this.gatewayCertificateRepository = gatewayCertificateRepository;
        this.certificateGenerator = certificateGenerator;
        this.certificateStore = certificateStore;
        this.certificateProperties = certificateProperties;
    }

    @Transactional
    public CertificateSyncResult ensureCertificate(Gateway gateway) {
        String hostname = buildHostname(gateway);
        String secretRef = buildSecretRef(gateway);
        CertificateProvider provider = certificateProperties.provider();

        Optional<GatewayCertificate> existingCertificate = gatewayCertificateRepository.findByGateway_Id(gateway.getId());
        GatewayCertificate certificate;
        boolean provisioningRequired;

        if (existingCertificate.isPresent()) {
            certificate = existingCertificate.get();
            boolean hostnameChanged = !hostname.equals(certificate.getHostname());
            boolean secretRefChanged = !secretRef.equals(certificate.getSecretRef());
            boolean providerChanged = provider != certificate.getProvider();
            if (hostnameChanged || secretRefChanged || providerChanged || certificate.getStatus() != CertificateStatus.ACTIVE) {
                certificate.updateProvisioningTarget(hostname, null, provider, secretRef);
                provisioningRequired = true;
            } else {
                provisioningRequired = false;
            }
        } else {
            certificate = GatewayCertificate.create(gateway, hostname, null, provider, secretRef);
            provisioningRequired = true;
        }

        GatewayCertificate savedCertificate = gatewayCertificateRepository.save(certificate);
        gateway.attachCertificate(savedCertificate);
        return new CertificateSyncResult(savedCertificate, provisioningRequired);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProvisionRequested(GatewayCertificateProvisionRequested event) {
        provisionCertificate(event.certificateId());
    }

    @Scheduled(fixedDelayString = "${app.certificate.retry-delay-ms:60000}")
    public void retryPendingCertificates() {
        List<GatewayCertificate> certificates = gatewayCertificateRepository.findByStatusIn(
                List.of(CertificateStatus.PENDING, CertificateStatus.FAILED)
        );

        for (GatewayCertificate certificate : certificates) {
            provisionCertificate(certificate.getId());
        }
    }

    @Scheduled(fixedDelayString = "${app.certificate.retry-delay-ms:60000}", initialDelayString = "${app.certificate.retry-delay-ms:60000}")
    public void reconcileActiveCertificates() {
        List<GatewayCertificate> certificates = gatewayCertificateRepository.findByStatus(CertificateStatus.ACTIVE);

        for (GatewayCertificate certificate : certificates) {
            if (certificate.getSecretRef() == null || certificate.getSecretRef().isBlank()) {
                logger.warn(
                        "Active certificate {} for gateway {} has no secret reference; reprovisioning",
                        certificate.getId(),
                        certificate.getGateway().getId()
                );
                provisionCertificate(certificate.getId());
                continue;
            }

            try {
                certificateStore.load(new CertificateReference(certificate.getSecretRef()));
            } catch (Exception exception) {
                logger.warn(
                        "Certificate material missing for gateway {} hostname {}; reprovisioning",
                        certificate.getGateway().getId(),
                        certificate.getHostname(),
                        exception
                );
                provisionCertificate(certificate.getId());
            }
        }
    }

    public void provisionCertificate(UUID certificateId) {
        GatewayCertificate certificate = gatewayCertificateRepository.findById(certificateId)
                .orElse(null);
        if (certificate == null) {
            return;
        }

        try {
            GeneratedCertificate generatedCertificate = certificateGenerator.generate(new CertificateRequest(
                    certificate.getHostname(),
                    List.of(certificate.getHostname())
            ));
            certificateStore.save(
                    new CertificateReference(certificate.getSecretRef()),
                    generatedCertificate.bundle()
            );
            certificate.markActive(generatedCertificate.issuedAt(), generatedCertificate.expiresAt());
            gatewayCertificateRepository.save(certificate);
        } catch (Exception exception) {
            certificate.markFailed();
            gatewayCertificateRepository.save(certificate);
            logger.error(
                    "Certificate provisioning failed for gateway {} hostname {} provider {}",
                    certificate.getGateway().getId(),
                    certificate.getHostname(),
                    certificate.getProvider(),
                    exception
            );
        }
    }

    public String buildHostname(Gateway gateway) {
        return gateway.getUniqueKey() + "." + gateway.getAppDomain().getDomainName();
    }

    private String buildSecretRef(Gateway gateway) {
        return "certificates/" + gateway.getId();
    }

    public record CertificateSyncResult(
            GatewayCertificate certificate,
            boolean provisioningRequired
    ) {
    }
}
