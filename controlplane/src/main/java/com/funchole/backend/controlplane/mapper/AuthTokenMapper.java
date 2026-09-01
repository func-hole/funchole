package com.funchole.backend.controlplane.mapper;

import com.funchole.backend.controlplane.dto.AuthTokenResponse;
import com.funchole.backend.controlplane.security.JwtToken;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AuthTokenMapper {

    @Mapping(target = "accessToken", source = "token")
    @Mapping(target = "tokenType", constant = "Bearer")
    AuthTokenResponse toResponse(JwtToken token);
}
