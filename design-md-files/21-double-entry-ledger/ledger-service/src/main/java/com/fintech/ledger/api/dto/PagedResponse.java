package com.fintech.ledger.api.dto;

import java.util.List;

public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        boolean hasMore
) {}
