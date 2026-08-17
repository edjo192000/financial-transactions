package com.financial.transactions.challenge.domain;

import com.financial.transactions.challenge.domain.exception.InvalidAmountException;
import com.financial.transactions.challenge.domain.exception.UnsupportedCurrencyException;

import java.math.BigDecimal;

public record Money(
        BigDecimal amount,
        String currency
) {
    public static Money of(BigDecimal amount, String currency) {
        if (amount.compareTo(BigDecimal.ONE) <= 0) {
            throw new InvalidAmountException("Amount must be greater than $1.00");
        }
        if (!TransactionConstants.MXN_CURRENCY.equals(currency)) {
            throw new UnsupportedCurrencyException("Currency not supported: " + currency);
        }
        return new Money(amount, currency);
    }
}
