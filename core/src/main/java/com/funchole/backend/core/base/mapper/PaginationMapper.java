package com.funchole.backend.core.base.mapper;

import com.funchole.backend.core.base.response.PaginationResponse;
import org.mapstruct.Mapper;
import org.springframework.data.domain.Page;

@Mapper(componentModel = "spring")
public interface PaginationMapper {

    default <T> PaginationResponse<T> toResponse(Page<T> data) {
        return new PaginationResponse<>(
                data.getContent(),
                data.getNumber() + 1,
                data.getSize(),
                data.getTotalElements(),
                data.getTotalPages(),
                data.isFirst(),
                data.isLast()
        );
    }
}
