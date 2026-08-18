package com.financial.transactions.challenge.provider.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProviderExecuteResponse(
        String transactionId,
        String status,
        BigDecimal balance,
        Instant executedAt
) {
}
