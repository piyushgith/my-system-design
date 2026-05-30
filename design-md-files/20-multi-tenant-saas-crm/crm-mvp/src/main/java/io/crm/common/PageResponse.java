package io.crm.common;

import java.util.List;

public record PageResponse<T>(
        List<T> data,
        PageMeta meta
) {
    public record PageMeta(
            long totalCount,
            int page,
            int pageSize,
            int totalPages
    ) {}

    public static <T> PageResponse<T> of(List<T> data, long totalCount, int page, int pageSize) {
        int totalPages = pageSize == 0 ? 0 : (int) Math.ceil((double) totalCount / pageSize);
        return new PageResponse<>(data, new PageMeta(totalCount, page, pageSize, totalPages));
    }
}
