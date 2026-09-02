package com.funchole.backend.controlplane.mapper;

import com.funchole.backend.controlplane.dto.ProfileResponse;
import com.funchole.backend.controlplane.entity.AppUser;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProfileMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "username", source = "username")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "fullName", source = "fullName")
    ProfileResponse toResponse(AppUser appUser);
}
