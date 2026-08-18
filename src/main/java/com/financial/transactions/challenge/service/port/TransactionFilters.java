package com.financial.transactions.challenge.service.port;

import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.TransactionType;

public record TransactionFilters(
        String accountId,
        TransactionStatus status,
        TransactionType type,
        int page,
        int limit
) {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    public TransactionFilters {
        if (page < 0) {
            throw new IllegalArgumentException("Page must be >= 0");
        }
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }

    public static TransactionFilters of(String accountId, TransactionStatus status,
                                        TransactionType type, Integer page, Integer limit) {
        return new TransactionFilters(
                accountId,
                status,
                type,
                page != null ? page : DEFAULT_PAGE,
                limit != null ? limit : DEFAULT_LIMIT
        );
    }
}
