package com.financial.transactions.challenge.service;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionRules;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.exception.IdempotencyKeyConflictException;
import com.financial.transactions.challenge.domain.exception.ProviderCommunicationException;
import com.financial.transactions.challenge.domain.exception.ProviderRejectedException;
import com.financial.transactions.challenge.domain.exception.ProviderTimeoutException;
import com.financial.transactions.challenge.service.port.ProviderResult;
import com.financial.transactions.challenge.service.port.TransactionProvider;
import com.financial.transactions.challenge.service.port.TransactionRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ExecuteTransactionService {

    private final TransactionRepository repository;
    private final TransactionProvider provider;
    private final Clock clock;

    public ExecuteTransactionService(TransactionRepository repository,
                                     TransactionProvider provider,
                                     Clock clock) {
        this.repository = repository;
        this.provider = provider;
        this.clock = clock;
    }

    public Transaction execute(ExecuteTransactionCommand command) {
        Optional<Transaction> existing = repository.findByIdempotencyKey(command.idempotencyKey());

        if (existing.isPresent()) {
            return handleExistingTransaction(existing.get(), command);
        }

        Money money = Money.of(command.amount(), command.currency());
        TransactionRules.validate(command.type(), money);

        Transaction result = callProviderAndBuildResult(command, money, UUID.randomUUID());

        return repository.save(result);
    }

    private Transaction handleExistingTransaction(Transaction existing, ExecuteTransactionCommand command) {
        Money requestedMoney = Money.of(command.amount(), command.currency());

        boolean matches = existing.matchesRequest(
                command.accountId(), command.type(), requestedMoney, command.description());

        if (!matches) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency-Key '" + command.idempotencyKey()
                            + "' was already used with different transaction data");
        }

        if (existing.status() != TransactionStatus.FAILED) {
            return existing;
        }

        Transaction retried = callProviderAndBuildResult(command, requestedMoney, existing.id());
        return repository.save(retried);
    }

    private Transaction callProviderAndBuildResult(ExecuteTransactionCommand command, Money money, UUID id) {
        Instant now = Instant.now(clock);

        try {
            ProviderResult providerResult = provider.execute(command.accountId(), command.type(), money);

            return Transaction.executed(
                    id, command.idempotencyKey(), command.accountId(), command.type(), money,
                    command.description(), providerResult.providerTransactionId(), providerResult.balanceAfter(), now
            );

        } catch (ProviderRejectedException e) {
            return Transaction.rejected(
                    id, command.idempotencyKey(), command.accountId(), command.type(), money,
                    command.description(), now
            );

        } catch (ProviderTimeoutException | ProviderCommunicationException e) {
            Transaction failedTransaction = Transaction.failed(
                    id, command.idempotencyKey(), command.accountId(), command.type(), money,
                    command.description(), e.getMessage(), now
            );
            repository.save(failedTransaction);
            throw e;
        }
    }
}
