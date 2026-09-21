package com.financial.transactions.challenge.service.port;

import com.financial.transactions.challenge.domain.Transaction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {
    Transaction save(Transaction transaction);

    /**
     * Attempts to atomically claim a new Idempotency-Key by inserting a PENDING row.
     * Backed by {@code INSERT ... ON CONFLICT (idempotency_key) DO NOTHING} — the database's
     * unique constraint is the actual mutual-exclusion mechanism, not application-level locking.
     *
     * @return true if this call won the reservation (0 rows existed for that key before this
     *         call); false if another concurrent request already reserved it first.
     */
    boolean tryReserve(Transaction pendingTransaction);

    Optional<Transaction> findById(UUID id);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    List<Transaction> findAll(TransactionFilters filters);
}
