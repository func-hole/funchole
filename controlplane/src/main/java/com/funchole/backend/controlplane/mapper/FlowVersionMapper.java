package com.funchole.backend.controlplane.mapper;

import com.funchole.backend.controlplane.dto.FlowVersionResponse;
import com.funchole.backend.controlplane.entity.FlowVersion;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface FlowVersionMapper {

    @Mapping(target = "flowId", source = "flow.id")
    @Mapping(target = "steps", ignore = true)
    FlowVersionResponse toResponse(FlowVersion flowVersion);
}
