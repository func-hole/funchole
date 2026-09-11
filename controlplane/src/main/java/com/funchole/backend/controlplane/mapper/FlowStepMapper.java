package com.funchole.backend.controlplane.mapper;

import com.funchole.backend.controlplane.dto.FlowStepResponse;
import com.funchole.backend.controlplane.entity.FlowStep;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface FlowStepMapper {

    @Mapping(target = "flowVersionId", source = "flowVersion.id")
    FlowStepResponse toResponse(FlowStep flowStep);
}
