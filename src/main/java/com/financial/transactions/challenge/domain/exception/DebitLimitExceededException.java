package com.financial.transactions.challenge.domain.exception;

public class DebitLimitExceededException extends RuntimeException {
    public DebitLimitExceededException(String message) {
        super(message);
    }
}
