package com.financial.transactions.challenge.domain.exception;

public class ProviderRejectedException extends RuntimeException {

    private final String code;

    public ProviderRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
