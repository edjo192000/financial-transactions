package com.financial.transactions.challenge.domain.exception;

/**
 * Thrown when a concurrent request reserved the same Idempotency-Key and, after the bounded
 * polling window, the reservation still hasn't resolved into a terminal status (EXECUTED,
 * REJECTED or FAILED). The caller has no new information to act on yet, so the controller
 * maps this to 202 Accepted with a Location header instead of blocking indefinitely.
 */
public class TransactionStillPendingException extends RuntimeException {

    private final String idempotencyKey;

    public TransactionStillPendingException(String idempotencyKey) {
        super("Transaction with Idempotency-Key '" + idempotencyKey
                + "' is still being processed by a concurrent request");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
