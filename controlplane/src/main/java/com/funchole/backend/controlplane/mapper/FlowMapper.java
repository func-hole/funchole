package com.funchole.backend.controlplane.mapper;

import com.funchole.backend.controlplane.dto.FlowResponse;
import com.funchole.backend.controlplane.entity.Flow;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface FlowMapper {

    @Mapping(target = "gatewayId", source = "gateway.id")
    @Mapping(target = "gatewayName", source = "gateway.name")
    FlowResponse toResponse(Flow flow);
}
