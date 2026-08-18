package com.financial.transactions.challenge.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(
        UUID id,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        String description,
        TransactionStatus status,
        String providerTransactionId,
        BigDecimal balanceAfter,
        String failureReason,
        Instant createdAt
) {

    public static TransactionResponse from(Transaction tx) {
        return new TransactionResponse(
                tx.id(),
                tx.accountId(),
                tx.type(),
                tx.money().amount(),
                tx.money().currency(),
                tx.description(),
                tx.status(),
                tx.providerTransactionId(),
                tx.balanceAfter(),
                tx.failureReason(),
                tx.createdAt()
        );
    }
}
