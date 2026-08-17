package com.financial.transactions.challenge.domain;

public enum TransactionStatus {
    EXECUTED,
    REJECTED,
    FAILED // Transaction error vs the provider
}
