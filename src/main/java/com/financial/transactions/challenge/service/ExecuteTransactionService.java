package com.financial.transactions.challenge.service;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionRules;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.exception.IdempotencyKeyConflictException;
import com.financial.transactions.challenge.domain.exception.ProviderCommunicationException;
import com.financial.transactions.challenge.domain.exception.ProviderRejectedException;
import com.financial.transactions.challenge.domain.exception.ProviderTimeoutException;
import com.financial.transactions.challenge.domain.exception.TransactionStillPendingException;
import com.financial.transactions.challenge.provider.ProviderProperties;
import com.financial.transactions.challenge.service.port.ProviderResult;
import com.financial.transactions.challenge.service.port.TransactionProvider;
import com.financial.transactions.challenge.service.port.TransactionRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ExecuteTransactionService {

    private final TransactionRepository repository;
    private final TransactionProvider provider;
    private final Clock clock;
    private final ProviderProperties properties;

    public ExecuteTransactionService(TransactionRepository repository,
                                     TransactionProvider provider,
                                     Clock clock,
                                     ProviderProperties properties) {
        this.repository = repository;
        this.provider = provider;
        this.clock = clock;
        this.properties = properties;
    }

    public Transaction execute(ExecuteTransactionCommand command) {
        Optional<Transaction> existing = repository.findByIdempotencyKey(command.idempotencyKey());

        if (existing.isPresent()) {
            return handleExistingTransaction(existing.get(), command);
        }

        Money money = Money.of(command.amount(), command.currency());
        TransactionRules.validate(command.type(), money);

        // Write-ahead: the PENDING row is the single source of truth for "an attempt is in
        // flight", written and durable BEFORE the provider is ever called. This closes two gaps
        // at once: (1) a second concurrent request with the same new Idempotency-Key can no
        // longer slip past the check-then-act race and call the provider a second time, because
        // the database's unique constraint on idempotency_key — not application code — decides
        // who wins; (2) if the provider call succeeds but the final save() afterward fails (DB
        // blip, network partition), the attempt is never silently lost — there's already a
        // durable PENDING row identifying exactly which transaction is in an unknown state and
        // needs reconciliation, instead of a provider-side execution with zero trace on our side.
        UUID id = UUID.randomUUID();
        Instant reservedAt = Instant.now(clock);
        Transaction pending = Transaction.pending(
                id, command.idempotencyKey(), command.accountId(), command.type(), money,
                command.description(), reservedAt);

        boolean wonReservation = repository.tryReserve(pending);

        if (!wonReservation) {
            // Lost the race: another concurrent request already reserved this exact key.
            // Don't call the provider a second time — wait, bounded, for that request to
            // resolve the reservation, then treat its result the same way a normal
            // idempotent replay would.
            Transaction resolved = waitForResolution(command.idempotencyKey());
            return handleExistingTransaction(resolved, command);
        }

        Transaction result = callProviderAndBuildResult(command, money, id);
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

        if (existing.status() == TransactionStatus.PENDING) {
            // Someone else's reservation, still unresolved when we looked it up directly
            // (rather than via the losing-the-race path above) — e.g. a client retried the
            // HTTP request itself while the first attempt was still talking to the provider.
            Transaction resolved = waitForResolution(command.idempotencyKey());
            return handleExistingTransaction(resolved, command);
        }

        if (existing.status() != TransactionStatus.FAILED) {
            return existing;
        }

        Transaction retried = callProviderAndBuildResult(command, requestedMoney, existing.id());
        return repository.save(retried);
    }

    /**
     * Polls, bounded, for a PENDING reservation made by another concurrent request to resolve
     * into a terminal-or-retryable status. Deliberately short (configured via
     * app.idempotency.poll-interval / poll-attempts, ~450ms total by default) so a Tomcat thread
     * is never held hostage waiting on another request's provider call. If the budget runs out
     * before resolution, throws TransactionStillPendingException — the controller maps that to
     * 202 Accepted with a Location the client can poll on its own terms, instead of either
     * blocking indefinitely or guessing at an outcome.
     */
    private Transaction waitForResolution(String idempotencyKey) {
        Duration pollInterval = properties.idempotency().pollInterval();
        int pollAttempts = properties.idempotency().pollAttempts();

        for (int attempt = 0; attempt < pollAttempts; attempt++) {
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransactionStillPendingException(idempotencyKey);
            }

            Optional<Transaction> current = repository.findByIdempotencyKey(idempotencyKey);
            if (current.isPresent() && current.get().status() != TransactionStatus.PENDING) {
                return current.get();
            }
        }

        throw new TransactionStillPendingException(idempotencyKey);
    }

    private Transaction callProviderAndBuildResult(ExecuteTransactionCommand command, Money money, UUID id) {
        Instant now = Instant.now(clock);

        try {
            ProviderResult providerResult = provider.execute(
                    command.idempotencyKey(), command.accountId(), command.type(), money);

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
