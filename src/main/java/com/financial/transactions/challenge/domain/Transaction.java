package com.financial.transactions.challenge.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Transaction(
        UUID id,
        String idempotencyKey,
        String accountId,
        TransactionType type,
        Money money,
        String description,
        TransactionStatus status,
        String providerTransactionId,
        BigDecimal balanceAfter,
        Instant createdAt
) {
}
