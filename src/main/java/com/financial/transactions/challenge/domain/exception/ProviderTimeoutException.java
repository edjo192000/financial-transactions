package com.financial.transactions.challenge.domain.exception;

public class ProviderTimeoutException extends RuntimeException {

    public ProviderTimeoutException(String message) {
        super(message);
    }

    public ProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
