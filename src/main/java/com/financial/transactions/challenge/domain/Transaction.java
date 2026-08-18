package com.financial.transactions.challenge.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;

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
        String failureReason,
        Instant createdAt
) {

    public static Transaction executed(String idempotencyKey, String accountId, TransactionType type,
                                       Money money, String description, String providerTransactionId,
                                       BigDecimal balanceAfter, Instant createdAt) {
        return new Transaction(
                UUID.randomUUID(), idempotencyKey, accountId, type, money, description,
                TransactionStatus.EXECUTED, providerTransactionId, balanceAfter,null, createdAt
        );
    }

    public static Transaction rejected(String idempotencyKey, String accountId, TransactionType type,
                                       Money money, String description, Instant createdAt) {
        return new Transaction(
                UUID.randomUUID(), idempotencyKey, accountId, type, money, description,
                TransactionStatus.REJECTED, null, null,null, createdAt
        );
    }

    public static Transaction failed(String idempotencyKey, String accountId, TransactionType type,
                                     Money money, String description, String failureReason, Instant createdAt) {
        return new Transaction(
                UUID.randomUUID(), idempotencyKey, accountId, type, money, description,
                TransactionStatus.FAILED, null, null, failureReason, createdAt
        );
    }

    public boolean matchesRequest(String accountId, TransactionType type, Money money, String description) {
        return this.accountId.equals(accountId)
                && this.type == type
                && this.money.equals(money)
                && Objects.equals(this.description, description);
    }
}
