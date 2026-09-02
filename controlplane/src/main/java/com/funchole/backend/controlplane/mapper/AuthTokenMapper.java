package com.funchole.backend.controlplane.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.funchole.backend.controlplane.dto.AuthTokenResponse;
import com.funchole.backend.controlplane.security.JwtToken;

@Mapper(componentModel = "spring")
public interface AuthTokenMapper {

    @Mapping(target = "accessToken", source = "token")
    @Mapping(target = "tokenType", constant = "Bearer")
    @Mapping(target = "passwordChangeRequired", source = "passwordChangeRequired")
    AuthTokenResponse toResponse(JwtToken token);
}
