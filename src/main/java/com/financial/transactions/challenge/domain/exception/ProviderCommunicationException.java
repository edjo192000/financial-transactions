package com.financial.transactions.challenge.domain.exception;

public class ProviderCommunicationException extends RuntimeException {

    public ProviderCommunicationException(String message) {
        super(message);
    }

    public ProviderCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
