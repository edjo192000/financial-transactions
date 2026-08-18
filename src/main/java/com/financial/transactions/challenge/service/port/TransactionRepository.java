package com.financial.transactions.challenge.service.port;

import com.financial.transactions.challenge.domain.Transaction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {
    Transaction save(Transaction transaction);

    Optional<Transaction> findById(UUID id);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    List<Transaction> findAll(TransactionFilters filters);
}
