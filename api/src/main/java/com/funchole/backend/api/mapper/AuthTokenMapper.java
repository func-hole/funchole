package com.funchole.backend.api.mapper;

import com.funchole.backend.api.dto.AuthTokenResponse;
import com.funchole.backend.api.security.JwtToken;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AuthTokenMapper {

    @Mapping(target = "accessToken", source = "token")
    @Mapping(target = "tokenType", constant = "Bearer")
    AuthTokenResponse toResponse(JwtToken token);
}
