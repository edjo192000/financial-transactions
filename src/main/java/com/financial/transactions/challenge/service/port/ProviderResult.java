package com.financial.transactions.challenge.service.port;

import java.math.BigDecimal;
import java.time.Instant;

public record ProviderResult(
        String providerTransactionId,
        BigDecimal balanceAfter,
        Instant executedAt
) {
}
