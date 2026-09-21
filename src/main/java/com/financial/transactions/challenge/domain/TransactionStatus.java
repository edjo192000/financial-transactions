package com.financial.transactions.challenge.domain;

public enum TransactionStatus {
    PENDING,  // Reservation written before calling the provider; not yet resolved
    EXECUTED,
    REJECTED,
    FAILED // Transaction error vs the provider
}
