package com.financial.transactions.challenge.service;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.service.port.TransactionFilters;
import com.financial.transactions.challenge.service.port.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryTransactionServiceTest {

    @Mock
    private TransactionRepository repository;

    private QueryTransactionService service;

    @BeforeEach
    void setUp() {
        service = new QueryTransactionService(repository);
    }

    private TransactionFilters aFilters(String accountId, TransactionStatus status, TransactionType type,
                                         int page, int limit) {
        return new TransactionFilters(accountId, status, type, page, limit);
    }

    private Transaction aTransaction(String accountId) {
        return Transaction.executed(
                "idem-" + accountId, accountId, TransactionType.CREDIT,
                new Money(new BigDecimal("100.00"), "MXN"), "Test transaction",
                "prov-1", new BigDecimal("500.00"), Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    @Nested
    @DisplayName("Delegation")
    class Delegation {

        @Test
        @DisplayName("GIVEN filters with accountId, status and type populated, WHEN querying, THEN repository is called exactly once with that same filters instance")
        void delegatesToRepository() {
            // given
            TransactionFilters filters = aFilters("acc-1", TransactionStatus.EXECUTED, TransactionType.CREDIT, 0, 20);
            when(repository.findAll(any())).thenReturn(List.of());

            // when
            service.query(filters);

            // then
            verify(repository, times(1)).findAll(eq(filters));
        }

        @Test
        @DisplayName("GIVEN repository.findAll returns a list of transactions, WHEN querying, THEN the service returns that exact same list unaltered")
        void returnsResultUnaltered() {
            // given
            List<Transaction> transactions = List.of(aTransaction("acc-1"), aTransaction("acc-2"));
            when(repository.findAll(any())).thenReturn(transactions);
            TransactionFilters filters = aFilters(null, null, null, 0, 20);

            // when
            List<Transaction> result = service.query(filters);

            // then
            assertThat(result).isSameAs(transactions);
            assertThat(result).containsExactlyElementsOf(transactions);
        }

        @Test
        @DisplayName("GIVEN repository.findAll returns an empty list, WHEN querying, THEN the service returns an empty list without throwing")
        void returnsEmptyList() {
            // given
            when(repository.findAll(any())).thenReturn(List.of());
            TransactionFilters filters = aFilters(null, null, null, 0, 20);

            // when
            List<Transaction> result = service.query(filters);

            // then
            assertThat(result).isNotNull();
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("GIVEN filters with no criteria at all, WHEN querying, THEN it still delegates correctly without throwing")
        void delegatesWithNoCriteria() {
            // given
            when(repository.findAll(any())).thenReturn(List.of());
            TransactionFilters filters = aFilters(null, null, null, 0, 20);

            // when
            List<Transaction> result = service.query(filters);

            // then
            assertThat(result).isEmpty();
            verify(repository, times(1)).findAll(eq(filters));
        }
    }

    @Nested
    @DisplayName("Pagination passthrough")
    class PaginationPassthrough {

        @Test
        @DisplayName("GIVEN filters with page=0 and limit=20, WHEN querying, THEN the repository receives exactly page=0 and limit=20")
        void passesPageZeroAndLimitTwenty() {
            // given
            TransactionFilters filters = aFilters(null, null, null, 0, 20);
            ArgumentCaptor<TransactionFilters> captor = ArgumentCaptor.forClass(TransactionFilters.class);
            when(repository.findAll(captor.capture())).thenReturn(List.of());

            // when
            service.query(filters);

            // then
            assertThat(captor.getValue().page()).isEqualTo(0);
            assertThat(captor.getValue().limit()).isEqualTo(20);
        }

        @Test
        @DisplayName("GIVEN filters with page=5 and limit=100, WHEN querying, THEN the repository receives exactly those values without modification")
        void passesPageFiveAndLimitHundred() {
            // given
            TransactionFilters filters = aFilters(null, null, null, 5, 100);
            ArgumentCaptor<TransactionFilters> captor = ArgumentCaptor.forClass(TransactionFilters.class);
            when(repository.findAll(captor.capture())).thenReturn(List.of());

            // when
            service.query(filters);

            // then
            assertThat(captor.getValue().page()).isEqualTo(5);
            assertThat(captor.getValue().limit()).isEqualTo(100);
        }

        @Test
        @DisplayName("GIVEN two consecutive calls with different page values, WHEN querying for each, THEN each call delegates with its own page to the repository")
        void passesDistinctPagePerCall() {
            // given
            TransactionFilters firstPage = aFilters("acc-1", null, null, 0, 20);
            TransactionFilters secondPage = aFilters("acc-1", null, null, 1, 20);
            when(repository.findAll(any())).thenReturn(List.of());

            // when
            service.query(firstPage);
            service.query(secondPage);

            // then
            verify(repository, times(1)).findAll(eq(firstPage));
            verify(repository, times(1)).findAll(eq(secondPage));
        }
    }
}
