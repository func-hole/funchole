package com.funchole.backend.controlplane.mapper;

import com.funchole.backend.controlplane.dto.DomainResponse;
import com.funchole.backend.controlplane.entity.AppDomain;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DomainMapper {

    DomainResponse toResponse(AppDomain appDomain);
}
