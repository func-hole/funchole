package com.funchole.backend.controlplane.mapper;

import com.funchole.backend.controlplane.dto.CertificateSummaryResponse;
import com.funchole.backend.controlplane.dto.GatewayResponse;
import com.funchole.backend.controlplane.entity.GatewayCertificate;
import com.funchole.backend.controlplane.entity.Gateway;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface GatewayMapper {

    @Mapping(target = "appDomainId", source = "appDomain.id")
    @Mapping(target = "domainName", source = "appDomain.domainName")
    GatewayResponse toResponse(Gateway gateway);

    CertificateSummaryResponse toCertificateSummary(GatewayCertificate certificate);
}
