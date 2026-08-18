package com.financial.transactions.challenge.repository;

import com.financial.transactions.challenge.AbstractIntegrationTest;
import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.service.port.TransactionFilters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JdbcTransactionRepository")
class JdbcTransactionRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTransactionRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("DELETE FROM transactions").update();
    }

    private Transaction anExecutedTransaction(String accountId, TransactionType type, BigDecimal amount) {
        return new Transaction(
                UUID.randomUUID(),
                "idem-" + UUID.randomUUID(),
                accountId,
                type,
                new Money(amount, "MXN"),
                "Test transaction",
                TransactionStatus.EXECUTED,
                "provider-txn-" + UUID.randomUUID(),
                new BigDecimal("5500.00"),
                null,
                Instant.now()
        );
    }

    @Nested
    @DisplayName("saving and retrieving by id")
    class SavingAndRetrievingById {

        @Test
        @DisplayName("Given an executed transaction, when saved and retrieved, then all fields match")
        void savesAndRetrievesExecutedTransaction() {
            // given
            Transaction transaction = anExecutedTransaction("acc-123", TransactionType.CREDIT, new BigDecimal("1500.00"));

            // when
            repository.save(transaction);
            Optional<Transaction> found = repository.findById(transaction.id());

            // then
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(transaction.id());
            assertThat(found.get().accountId()).isEqualTo("acc-123");
            assertThat(found.get().type()).isEqualTo(TransactionType.CREDIT);
            assertThat(found.get().money().amount()).isEqualByComparingTo("1500.00");
            assertThat(found.get().money().currency()).isEqualTo("MXN");
            assertThat(found.get().status()).isEqualTo(TransactionStatus.EXECUTED);
            assertThat(found.get().providerTransactionId()).isEqualTo(transaction.providerTransactionId());
            assertThat(found.get().balanceAfter()).isEqualByComparingTo("5500.00");
        }

        @Test
        @DisplayName("Given no transaction exists, when finding by id, then returns empty")
        void returnsEmptyWhenIdNotFound() {
            // given
            UUID nonExistentId = UUID.randomUUID();

            // when
            Optional<Transaction> found = repository.findById(nonExistentId);

            // then
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("Given a rejected transaction, when saved and retrieved, then provider fields are null")
        void savesAndRetrievesRejectedTransactionWithNullProviderFields() {
            // given
            Transaction rejected = new Transaction(
                    UUID.randomUUID(),
                    "idem-" + UUID.randomUUID(),
                    "acc-456",
                    TransactionType.DEBIT,
                    new Money(new BigDecimal("50.00"), "MXN"),
                    "Rejected test",
                    TransactionStatus.REJECTED,
                    null,
                    null,
                    null,
                    Instant.now()
            );

            // when
            repository.save(rejected);
            Optional<Transaction> found = repository.findById(rejected.id());

            // then
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo(TransactionStatus.REJECTED);
            assertThat(found.get().providerTransactionId()).isNull();
            assertThat(found.get().balanceAfter()).isNull();
        }

        @Test
        @DisplayName("Given a failed transaction, when saved and retrieved, then the failure reason is preserved")
        void savesAndRetrievesFailedTransactionWithFailureReason() {
            // given
            Transaction failed = Transaction.failed(
                    "idem-" + UUID.randomUUID(),
                    "acc-789",
                    TransactionType.DEBIT,
                    new Money(new BigDecimal("75.00"), "MXN"),
                    "Failed test",
                    "Provider timed out",
                    Instant.now()
            );

            // when
            repository.save(failed);
            Optional<Transaction> found = repository.findById(failed.id());

            // then
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo(TransactionStatus.FAILED);
            assertThat(found.get().providerTransactionId()).isNull();
            assertThat(found.get().balanceAfter()).isNull();
            assertThat(found.get().failureReason()).isEqualTo("Provider timed out");
        }

        @Test
        @DisplayName("Given a saved FAILED transaction, when saving again with the same id but status EXECUTED, then findById returns the updated version and created_at is preserved")
        void upsertsSameIdFromFailedToExecuted() {
            // given
            Instant originalCreatedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
            Transaction failed = new Transaction(
                    UUID.randomUUID(),
                    "idem-" + UUID.randomUUID(),
                    "acc-999",
                    TransactionType.DEBIT,
                    new Money(new BigDecimal("80.00"), "MXN"),
                    "Retry test",
                    TransactionStatus.FAILED,
                    null,
                    null,
                    "Provider timed out",
                    originalCreatedAt
            );
            repository.save(failed);

            Transaction executed = new Transaction(
                    failed.id(),
                    failed.idempotencyKey(),
                    failed.accountId(),
                    failed.type(),
                    failed.money(),
                    failed.description(),
                    TransactionStatus.EXECUTED,
                    "provider-txn-retry",
                    new BigDecimal("920.00"),
                    null,
                    Instant.now()
            );

            // when
            repository.save(executed);
            Optional<Transaction> found = repository.findById(failed.id());

            // then
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo(TransactionStatus.EXECUTED);
            assertThat(found.get().providerTransactionId()).isEqualTo("provider-txn-retry");
            assertThat(found.get().balanceAfter()).isEqualByComparingTo("920.00");
            assertThat(found.get().failureReason()).isNull();
            assertThat(found.get().createdAt()).isEqualTo(originalCreatedAt);
        }
    }

    @Nested
    @DisplayName("finding by idempotency key")
    class FindingByIdempotencyKey {

        @Test
        @DisplayName("Given a transaction exists, when finding by its idempotency key, then it is found")
        void findsTransactionByItsKey() {
            // given
            Transaction transaction = anExecutedTransaction("acc-123", TransactionType.CREDIT, new BigDecimal("100.00"));
            repository.save(transaction);

            // when
            Optional<Transaction> found = repository.findByIdempotencyKey(transaction.idempotencyKey());

            // then
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(transaction.id());
        }

        @Test
        @DisplayName("Given no transaction has that key, when finding by idempotency key, then returns empty")
        void returnsEmptyWhenKeyNotFound() {
            // given
            String unusedKey = "non-existent-key";

            // when
            Optional<Transaction> found = repository.findByIdempotencyKey(unusedKey);

            // then
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("Given an idempotency key already used, when saving another transaction with it, then the database rejects it")
        void rejectsDuplicateKeyOnSave() {
            // given
            String sharedKey = "duplicate-key-" + UUID.randomUUID();
            Transaction first = new Transaction(
                    UUID.randomUUID(), sharedKey, "acc-123", TransactionType.CREDIT,
                    new Money(new BigDecimal("100.00"), "MXN"), "First", TransactionStatus.EXECUTED,
                    "provider-1", new BigDecimal("100.00"), null, Instant.now()
            );
            Transaction duplicate = new Transaction(
                    UUID.randomUUID(), sharedKey, "acc-123", TransactionType.CREDIT,
                    new Money(new BigDecimal("200.00"), "MXN"), "Duplicate", TransactionStatus.EXECUTED,
                    "provider-2", new BigDecimal("300.00"), null, Instant.now()
            );
            repository.save(first);

            // when
            Executable savingTheDuplicate = () -> repository.save(duplicate);

            // then
            assertThatThrownBy(savingTheDuplicate::execute)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("filtering results")
    class FilteringResults {

        @BeforeEach
        void givenASetOfTransactionsAcrossAccountsTypesAndStatuses() {
            repository.save(anExecutedTransaction("acc-1", TransactionType.CREDIT, new BigDecimal("100.00")));
            repository.save(anExecutedTransaction("acc-1", TransactionType.DEBIT, new BigDecimal("50.00")));
            repository.save(anExecutedTransaction("acc-2", TransactionType.CREDIT, new BigDecimal("300.00")));

            Transaction rejected = new Transaction(
                    UUID.randomUUID(), "idem-" + UUID.randomUUID(), "acc-1", TransactionType.DEBIT,
                    new Money(new BigDecimal("20.00"), "MXN"), "Rejected", TransactionStatus.REJECTED,
                    null, null, null, Instant.now()
            );
            repository.save(rejected);
        }

        @Test
        @DisplayName("Given transactions across accounts, when filtering by account id, then only that account's transactions are returned")
        void filtersByAccountId() {
            // given
            TransactionFilters filters = TransactionFilters.of("acc-1", null, null, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(3);
            assertThat(results).allMatch(tx -> tx.accountId().equals("acc-1"));
        }

        @Test
        @DisplayName("Given transactions with different statuses, when filtering by status, then only transactions with that status are returned")
        void filtersByStatus() {
            // given
            TransactionFilters filters = TransactionFilters.of(null, TransactionStatus.REJECTED, null, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).status()).isEqualTo(TransactionStatus.REJECTED);
        }

        @Test
        @DisplayName("Given transactions of different types, when filtering by type, then only transactions of that type are returned")
        void filtersByType() {
            // given
            TransactionFilters filters = TransactionFilters.of(null, null, TransactionType.CREDIT, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(2);
            assertThat(results).allMatch(tx -> tx.type() == TransactionType.CREDIT);
        }

        @Test
        @DisplayName("Given transactions across accounts, statuses and types, when combining account, status and type filters, then only the matching transaction is returned")
        void combinesAccountStatusAndTypeFilters() {
            // given
            TransactionFilters filters = TransactionFilters.of("acc-1", TransactionStatus.EXECUTED,
                    TransactionType.DEBIT, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).accountId()).isEqualTo("acc-1");
            assertThat(results.get(0).status()).isEqualTo(TransactionStatus.EXECUTED);
            assertThat(results.get(0).type()).isEqualTo(TransactionType.DEBIT);
        }

        @Test
        @DisplayName("Given an account with no transactions, when filtering, then returns an empty list")
        void returnsEmptyListForAccountWithNoTransactions() {
            // given
            TransactionFilters filters = TransactionFilters.of("acc-nonexistent", null, null, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Given no filters applied, when finding all, then returns every transaction")
        void returnsEveryTransactionWhenUnfiltered() {
            // given
            TransactionFilters filters = TransactionFilters.of(null, null, null, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(4);
        }
    }

    @Nested
    @DisplayName("paginating results")
    class Paginating {

        @BeforeEach
        void givenFiveTransactionsForTheSameAccount() {
            for (int i = 0; i < 5; i++) {
                repository.save(anExecutedTransaction("acc-page", TransactionType.CREDIT, new BigDecimal("10.00")));
            }
        }

        @Test
        @DisplayName("Given five transactions for the same account, when the limit is two, then only two results are returned")
        void limitsResultsToPageSize() {
            // given
            TransactionFilters filters = TransactionFilters.of("acc-page", null, null, 0, 2);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("Given five transactions for the same account, when requesting consecutive pages, then results do not overlap")
        void consecutivePagesDoNotOverlap() {
            // given
            TransactionFilters firstPageFilter = TransactionFilters.of("acc-page", null, null, 0, 2);
            TransactionFilters secondPageFilter = TransactionFilters.of("acc-page", null, null, 1, 2);

            // when
            List<Transaction> firstPage = repository.findAll(firstPageFilter);
            List<Transaction> secondPage = repository.findAll(secondPageFilter);

            // then
            List<UUID> firstIds = firstPage.stream().map(Transaction::id).toList();
            List<UUID> secondIds = secondPage.stream().map(Transaction::id).toList();
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        }

        @Test
        @DisplayName("Given five transactions for the same account, when finding all, then results are ordered by created date descending")
        void ordersResultsByCreatedAtDescending() {
            // given
            TransactionFilters filters = TransactionFilters.of("acc-page", null, null, 0, 5);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).isSortedAccordingTo((a, b) -> b.createdAt().compareTo(a.createdAt()));
        }
    }
}
