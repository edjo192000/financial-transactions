package com.financial.transactions.challenge.domain;

import com.financial.transactions.challenge.domain.exception.DebitLimitExceededException;

import java.math.BigDecimal;

public final class TransactionRules {

    private static final BigDecimal MAX_DEBIT_AMOUNT = BigDecimal.valueOf(10_000);

    private TransactionRules() {
    }

    public static void validate(TransactionType type, Money money) {
        if (type == TransactionType.DEBIT && money.amount().compareTo(MAX_DEBIT_AMOUNT) > 0) {
            throw new DebitLimitExceededException(
                    "DEBIT amount cannot exceed $10,000.00, got: " + money.amount());
        }
    }
}
