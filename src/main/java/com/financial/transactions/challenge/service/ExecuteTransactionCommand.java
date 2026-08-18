package com.financial.transactions.challenge.service;

import com.financial.transactions.challenge.domain.TransactionType;

import java.math.BigDecimal;

public record ExecuteTransactionCommand (
        String idempotencyKey,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        String description
){
}
