package com.funchole.backend.controlplane.mapper;

import com.funchole.backend.controlplane.dto.FunctionResponse;
import com.funchole.backend.controlplane.entity.Function;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FunctionMapper {

    FunctionResponse toResponse(Function function);
}
