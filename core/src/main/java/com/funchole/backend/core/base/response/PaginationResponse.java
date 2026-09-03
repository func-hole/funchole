package com.funchole.backend.core.base.response;

import java.util.List;

public record PaginationResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
