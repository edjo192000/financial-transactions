package com.financial.transactions.challenge.controller.dto;

import com.financial.transactions.challenge.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ExecuteTransactionRequest (
        @NotBlank(message = "accountId is required")
        String accountId,

        @NotNull(message = "type is required")
        TransactionType type,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be positive")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        String currency,

        String description
){
}
