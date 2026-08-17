package com.financial.transactions.challenge.domain.exception;

public class ProviderRejectedException extends RuntimeException {
    public ProviderRejectedException(String message) {
        super(message);
    }
}
