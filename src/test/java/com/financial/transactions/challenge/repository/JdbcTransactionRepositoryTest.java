package com.financial.transactions.challenge.repository;

import com.financial.transactions.challenge.AbstractIntegrationTest;
import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.service.port.TransactionFilters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                Instant.now()
        );
    }

    @Nested
    class SavingAndRetrievingById {

        @Test
        void givenAnExecutedTransaction_whenSavedAndRetrieved_thenAllFieldsMatch() {
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
        void givenNoTransactionExists_whenFindingById_thenReturnsEmpty() {
            // given
            UUID nonExistentId = UUID.randomUUID();

            // when
            Optional<Transaction> found = repository.findById(nonExistentId);

            // then
            assertThat(found).isEmpty();
        }

        @Test
        void givenARejectedTransaction_whenSavedAndRetrieved_thenProviderFieldsAreNull() {
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
    }

    @Nested
    class FindingByIdempotencyKey {

        @Test
        void givenATransactionExists_whenFindingByItsIdempotencyKey_thenItIsFound() {
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
        void givenNoTransactionHasThatKey_whenFindingByIdempotencyKey_thenReturnsEmpty() {
            // given
            String unusedKey = "non-existent-key";

            // when
            Optional<Transaction> found = repository.findByIdempotencyKey(unusedKey);

            // then
            assertThat(found).isEmpty();
        }

        @Test
        void givenAnIdempotencyKeyAlreadyUsed_whenSavingAnotherTransactionWithIt_thenDatabaseRejectsIt() {
            // given
            String sharedKey = "duplicate-key-" + UUID.randomUUID();
            Transaction first = new Transaction(
                    UUID.randomUUID(), sharedKey, "acc-123", TransactionType.CREDIT,
                    new Money(new BigDecimal("100.00"), "MXN"), "First", TransactionStatus.EXECUTED,
                    "provider-1", new BigDecimal("100.00"), Instant.now()
            );
            Transaction duplicate = new Transaction(
                    UUID.randomUUID(), sharedKey, "acc-123", TransactionType.CREDIT,
                    new Money(new BigDecimal("200.00"), "MXN"), "Duplicate", TransactionStatus.EXECUTED,
                    "provider-2", new BigDecimal("300.00"), Instant.now()
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
    class FilteringResults {

        @BeforeEach
        void givenASetOfTransactionsAcrossAccountsTypesAndStatuses() {
            repository.save(anExecutedTransaction("acc-1", TransactionType.CREDIT, new BigDecimal("100.00")));
            repository.save(anExecutedTransaction("acc-1", TransactionType.DEBIT, new BigDecimal("50.00")));
            repository.save(anExecutedTransaction("acc-2", TransactionType.CREDIT, new BigDecimal("300.00")));

            Transaction rejected = new Transaction(
                    UUID.randomUUID(), "idem-" + UUID.randomUUID(), "acc-1", TransactionType.DEBIT,
                    new Money(new BigDecimal("20.00"), "MXN"), "Rejected", TransactionStatus.REJECTED,
                    null, null, Instant.now()
            );
            repository.save(rejected);
        }

        @Test
        void whenFilteringByAccountId_thenOnlyThatAccountsTransactionsAreReturned() {
            // given
            TransactionFilters filters = TransactionFilters.of("acc-1", null, null, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(3);
            assertThat(results).allMatch(tx -> tx.accountId().equals("acc-1"));
        }

        @Test
        void whenFilteringByStatus_thenOnlyTransactionsWithThatStatusAreReturned() {
            // given
            TransactionFilters filters = TransactionFilters.of(null, TransactionStatus.REJECTED, null, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).status()).isEqualTo(TransactionStatus.REJECTED);
        }

        @Test
        void whenFilteringByType_thenOnlyTransactionsOfThatTypeAreReturned() {
            // given
            TransactionFilters filters = TransactionFilters.of(null, null, TransactionType.CREDIT, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(2);
            assertThat(results).allMatch(tx -> tx.type() == TransactionType.CREDIT);
        }

        @Test
        void whenCombiningAccountStatusAndTypeFilters_thenOnlyMatchingTransactionIsReturned() {
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
        void givenAnAccountWithNoTransactions_whenFiltering_thenReturnsEmptyList() {
            // given
            TransactionFilters filters = TransactionFilters.of("acc-nonexistent", null, null, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).isEmpty();
        }

        @Test
        void givenNoFiltersApplied_whenFindingAll_thenReturnsEveryTransaction() {
            // given
            TransactionFilters filters = TransactionFilters.of(null, null, null, null, null);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(4);
        }
    }

    @Nested
    class Paginating {

        @BeforeEach
        void givenFiveTransactionsForTheSameAccount() {
            for (int i = 0; i < 5; i++) {
                repository.save(anExecutedTransaction("acc-page", TransactionType.CREDIT, new BigDecimal("10.00")));
            }
        }

        @Test
        void whenLimitIsTwo_thenOnlyTwoResultsAreReturned() {
            // given
            TransactionFilters filters = TransactionFilters.of("acc-page", null, null, 0, 2);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).hasSize(2);
        }

        @Test
        void whenRequestingConsecutivePages_thenResultsDoNotOverlap() {
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
        void whenFindingAll_thenResultsAreOrderedByCreatedAtDescending() {
            // given
            TransactionFilters filters = TransactionFilters.of("acc-page", null, null, 0, 5);

            // when
            List<Transaction> results = repository.findAll(filters);

            // then
            assertThat(results).isSortedAccordingTo((a, b) -> b.createdAt().compareTo(a.createdAt()));
        }
    }
}