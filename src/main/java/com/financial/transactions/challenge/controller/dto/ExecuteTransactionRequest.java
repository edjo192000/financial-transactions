package com.financial.transactions.challenge.controller.dto;

import com.financial.transactions.challenge.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ExecuteTransactionRequest (
        @NotBlank(message = "accountId is required")
        @Schema(description = "Identifier of the account the transaction is executed against.", example = "acc-123")
        String accountId,

        @NotNull(message = "type is required")
        @Schema(description = "Type of transaction.", example = "CREDIT")
        TransactionType type,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be positive")
        @Schema(description = "Transaction amount. Must be greater than $1.00; DEBIT transactions additionally cannot exceed $10,000.00.", example = "1500.00")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Schema(description = "Transaction currency. Only MXN is currently supported.", example = "MXN")
        String currency,

        String description
){
}
