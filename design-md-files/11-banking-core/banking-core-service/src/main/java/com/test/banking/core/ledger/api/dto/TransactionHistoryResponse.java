package com.test.banking.core.ledger.api.dto;

import java.util.List;

public record TransactionHistoryResponse(
        String accountId,
        List<TransactionLineResponse> transactions,
        Pagination pagination) {

    public record Pagination(int page, int size, long totalElements, int totalPages, boolean hasNext,
                             boolean truncated) {
    }
}
