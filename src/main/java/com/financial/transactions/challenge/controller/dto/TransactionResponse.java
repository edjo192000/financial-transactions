package com.financial.transactions.challenge.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

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

        @Schema(description = "Outcome of the transaction. EXECUTED and REJECTED are terminal; "
                + "FAILED means the provider call did not complete and can be retried with the same Idempotency-Key.")
        TransactionStatus status,

        @Schema(description = "Identifier assigned by the external provider. Present only when status is EXECUTED.", nullable = true)
        String providerTransactionId,

        @Schema(description = "Account balance after applying the transaction. Present only when status is EXECUTED.", nullable = true)
        BigDecimal balanceAfter,

        @Schema(description = "Reason the provider call failed. Present only when status is FAILED.", nullable = true)
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
