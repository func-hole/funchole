package com.funchole.backend.controlplane.repository;

import com.funchole.backend.certificate.CertificateStatus;
import com.funchole.backend.controlplane.entity.GatewayCertificate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayCertificateRepository extends JpaRepository<GatewayCertificate, UUID> {

    @EntityGraph(attributePaths = { "gateway", "gateway.appDomain" })
    Optional<GatewayCertificate> findById(UUID id);

    @EntityGraph(attributePaths = { "gateway", "gateway.appDomain" })
    Optional<GatewayCertificate> findByGateway_Id(UUID gatewayId);

    @EntityGraph(attributePaths = { "gateway", "gateway.appDomain" })
    List<GatewayCertificate> findByStatusIn(Collection<CertificateStatus> statuses);

    @EntityGraph(attributePaths = { "gateway", "gateway.appDomain" })
    List<GatewayCertificate> findByStatus(CertificateStatus status);
}
