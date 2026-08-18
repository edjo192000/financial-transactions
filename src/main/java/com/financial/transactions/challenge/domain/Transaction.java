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

    public static Transaction executed(UUID id, String idempotencyKey, String accountId, TransactionType type,
                                       Money money, String description, String providerTransactionId,
                                       BigDecimal balanceAfter, Instant createdAt) {
        return new Transaction(
                id, idempotencyKey, accountId, type, money, description,
                TransactionStatus.EXECUTED, providerTransactionId, balanceAfter, null, createdAt
        );
    }

    public static Transaction executed(String idempotencyKey, String accountId, TransactionType type,
                                       Money money, String description, String providerTransactionId,
                                       BigDecimal balanceAfter, Instant createdAt) {
        return executed(UUID.randomUUID(), idempotencyKey, accountId, type, money, description,
                providerTransactionId, balanceAfter, createdAt);
    }

    public static Transaction rejected(UUID id, String idempotencyKey, String accountId, TransactionType type,
                                       Money money, String description, Instant createdAt) {
        return new Transaction(
                id, idempotencyKey, accountId, type, money, description,
                TransactionStatus.REJECTED, null, null, null, createdAt
        );
    }

    public static Transaction rejected(String idempotencyKey, String accountId, TransactionType type,
                                       Money money, String description, Instant createdAt) {
        return rejected(UUID.randomUUID(), idempotencyKey, accountId, type, money, description, createdAt);
    }

    public static Transaction failed(UUID id, String idempotencyKey, String accountId, TransactionType type,
                                     Money money, String description, String failureReason, Instant createdAt) {
        return new Transaction(
                id, idempotencyKey, accountId, type, money, description,
                TransactionStatus.FAILED, null, null, failureReason, createdAt
        );
    }

    public static Transaction failed(String idempotencyKey, String accountId, TransactionType type,
                                     Money money, String description, String failureReason, Instant createdAt) {
        return failed(UUID.randomUUID(), idempotencyKey, accountId, type, money, description,
                failureReason, createdAt);
    }

    public boolean matchesRequest(String accountId, TransactionType type, Money money, String description) {
        return this.accountId.equals(accountId)
                && this.type == type
                && this.money.equals(money)
                && Objects.equals(this.description, description);
    }
}
