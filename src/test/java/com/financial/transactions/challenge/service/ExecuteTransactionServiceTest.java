package com.financial.transactions.challenge.service;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.domain.exception.DebitLimitExceededException;
import com.financial.transactions.challenge.domain.exception.IdempotencyKeyConflictException;
import com.financial.transactions.challenge.domain.exception.InvalidAmountException;
import com.financial.transactions.challenge.domain.exception.ProviderCommunicationException;
import com.financial.transactions.challenge.domain.exception.ProviderRejectedException;
import com.financial.transactions.challenge.domain.exception.ProviderTimeoutException;
import com.financial.transactions.challenge.domain.exception.UnsupportedCurrencyException;
import com.financial.transactions.challenge.service.port.ProviderResult;
import com.financial.transactions.challenge.service.port.TransactionProvider;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecuteTransactionServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String IDEMPOTENCY_KEY = "idem-key-1";
    private static final String ACCOUNT_ID = "acc-1";
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final String CURRENCY = "MXN";
    private static final String DESCRIPTION = "Test transaction";

    @Mock
    private TransactionRepository repository;

    @Mock
    private TransactionProvider provider;

    private ExecuteTransactionService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        service = new ExecuteTransactionService(repository, provider, fixedClock);
    }

    private ExecuteTransactionCommand aCommand(String idempotencyKey, String accountId, TransactionType type,
                                                BigDecimal amount, String currency, String description) {
        return new ExecuteTransactionCommand(idempotencyKey, accountId, type, amount, currency, description);
    }

    private ExecuteTransactionCommand aValidCommand() {
        return aCommand(IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, AMOUNT, CURRENCY, DESCRIPTION);
    }

    private ExecuteTransactionCommand aValidCommand(TransactionType type, BigDecimal amount) {
        return aCommand(IDEMPOTENCY_KEY, ACCOUNT_ID, type, amount, CURRENCY, DESCRIPTION);
    }

    private ProviderResult aProviderResult() {
        return new ProviderResult("prov-999", new BigDecimal("450.00"), FIXED_INSTANT);
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("Given no transaction exists with that idempotency key, when executing, then it continues the normal flow")
        void continuesNormalFlow() {
            // given
            ExecuteTransactionCommand command = aValidCommand();
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            when(provider.execute(any(), any(), any())).thenReturn(aProviderResult());
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            Transaction result = service.execute(command);

            // then
            assertThat(result.status()).isEqualTo(TransactionStatus.EXECUTED);
            verify(provider, times(1)).execute(any(), any(), any());
        }

        @Test
        @DisplayName("Given a transaction already exists with that idempotency key and status EXECUTED, when executing, then it returns the existing transaction without calling the provider or saving again")
        void returnsExecutedWithoutSideEffects() {
            // given
            Transaction existing = Transaction.executed(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, Money.of(AMOUNT, CURRENCY),
                    DESCRIPTION, "prov-123", new BigDecimal("500.00"), FIXED_INSTANT
            );
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

            ExecuteTransactionCommand command = aValidCommand();

            // when
            Transaction result = service.execute(command);

            // then
            assertThat(result).isEqualTo(existing);
            verify(provider, never()).execute(any(), any(), any());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Given a transaction already exists with that idempotency key and status REJECTED, when executing, then it returns the existing transaction without calling the provider or saving again")
        void returnsRejectedWithoutSideEffects() {
            // given
            Transaction existing = Transaction.rejected(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, Money.of(AMOUNT, CURRENCY),
                    DESCRIPTION, FIXED_INSTANT
            );
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

            ExecuteTransactionCommand command = aValidCommand();

            // when
            Transaction result = service.execute(command);

            // then
            assertThat(result).isEqualTo(existing);
            verify(provider, never()).execute(any(), any(), any());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Given a transaction already exists with that idempotency key and status FAILED, when executing, then the provider is invoked again")
        void retriesProviderForFailedTransaction() {
            // given
            Transaction existing = Transaction.failed(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, Money.of(AMOUNT, CURRENCY),
                    DESCRIPTION, "Provider did not respond within the socket timeout", FIXED_INSTANT
            );
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));
            when(provider.execute(any(), any(), any())).thenReturn(aProviderResult());
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            ExecuteTransactionCommand command = aValidCommand();

            // when
            service.execute(command);

            // then
            verify(provider, times(1)).execute(any(), any(), any());
        }

        @Test
        @DisplayName("Given a FAILED transaction exists and the retry succeeds, when executing, then the resulting Transaction has status EXECUTED and the same id as the original FAILED transaction")
        void preservesIdWhenRetrySucceeds() {
            // given
            Transaction existing = Transaction.failed(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, Money.of(AMOUNT, CURRENCY),
                    DESCRIPTION, "Provider did not respond within the socket timeout", FIXED_INSTANT
            );
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));
            when(provider.execute(any(), any(), any())).thenReturn(aProviderResult());
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            ExecuteTransactionCommand command = aValidCommand();

            // when
            Transaction result = service.execute(command);

            // then
            assertThat(result.status()).isEqualTo(TransactionStatus.EXECUTED);
            assertThat(result.id()).isEqualTo(existing.id());
        }

        @Test
        @DisplayName("Given a FAILED transaction exists and the retry fails again, when executing, then the exception propagates and repository.save is called with a Transaction preserving the original id")
        void preservesIdWhenRetryFailsAgain() {
            // given
            Transaction existing = Transaction.failed(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, Money.of(AMOUNT, CURRENCY),
                    DESCRIPTION, "Provider did not respond within the socket timeout", FIXED_INSTANT
            );
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));
            when(provider.execute(any(), any(), any()))
                    .thenThrow(new ProviderTimeoutException("Provider did not respond within the socket timeout"));
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

            ExecuteTransactionCommand command = aValidCommand();

            // when
            // then
            assertThatThrownBy(() -> service.execute(command))
                    .isInstanceOf(ProviderTimeoutException.class);

            assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.FAILED);
            assertThat(captor.getValue().id()).isEqualTo(existing.id());
        }

        @Test
        @DisplayName("Given a transaction already exists with that idempotency key but a different accountId, when executing, then it throws IdempotencyKeyConflictException")
        void throwsConflictExceptionOnDifferentAccountId() {
            // given
            Transaction existing = Transaction.executed(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, Money.of(AMOUNT, CURRENCY),
                    DESCRIPTION, "prov-123", new BigDecimal("500.00"), FIXED_INSTANT
            );
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

            ExecuteTransactionCommand command = aCommand(
                    IDEMPOTENCY_KEY, "acc-2", TransactionType.CREDIT, AMOUNT, CURRENCY, DESCRIPTION);

            // when
            // then
            assertThatThrownBy(() -> service.execute(command))
                    .isInstanceOf(IdempotencyKeyConflictException.class);
        }

        @Test
        @DisplayName("Given a transaction already exists with that idempotency key but a different amount, when executing, then it throws IdempotencyKeyConflictException")
        void throwsConflictExceptionOnDifferentAmount() {
            // given
            Transaction existing = Transaction.executed(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, Money.of(AMOUNT, CURRENCY),
                    DESCRIPTION, "prov-123", new BigDecimal("500.00"), FIXED_INSTANT
            );
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

            ExecuteTransactionCommand command = aCommand(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, new BigDecimal("200.00"), CURRENCY, DESCRIPTION);

            // when
            // then
            assertThatThrownBy(() -> service.execute(command))
                    .isInstanceOf(IdempotencyKeyConflictException.class);
        }

        @Test
        @DisplayName("Given a transaction already exists with that idempotency key but a different type, when executing, then it throws IdempotencyKeyConflictException")
        void throwsConflictExceptionOnDifferentType() {
            // given
            Transaction existing = Transaction.executed(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, Money.of(AMOUNT, CURRENCY),
                    DESCRIPTION, "prov-123", new BigDecimal("500.00"), FIXED_INSTANT
            );
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

            ExecuteTransactionCommand command = aCommand(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.DEBIT, AMOUNT, CURRENCY, DESCRIPTION);

            // when
            // then
            assertThatThrownBy(() -> service.execute(command))
                    .isInstanceOf(IdempotencyKeyConflictException.class);
        }
    }

    @Nested
    @DisplayName("Business rule validation")
    class BusinessRuleValidation {

        @Test
        @DisplayName("Given an amount less than or equal to $1.00, when executing, then it throws InvalidAmountException and the provider is never invoked")
        void rejectsAmountAtOrBelowMinimum() {
            // given
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            ExecuteTransactionCommand command = aValidCommand(TransactionType.CREDIT, new BigDecimal("1.00"));

            // when
            // then
            assertThatThrownBy(() -> service.execute(command))
                    .isInstanceOf(InvalidAmountException.class);
            verify(provider, never()).execute(any(), any(), any());
        }

        @Test
        @DisplayName("Given a DEBIT with an amount greater than $10,000.00, when executing, then it throws DebitLimitExceededException and the provider is never invoked")
        void rejectsDebitAboveLimit() {
            // given
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            ExecuteTransactionCommand command = aValidCommand(TransactionType.DEBIT, new BigDecimal("10000.01"));

            // when
            // then
            assertThatThrownBy(() -> service.execute(command))
                    .isInstanceOf(DebitLimitExceededException.class);
            verify(provider, never()).execute(any(), any(), any());
        }

        @Test
        @DisplayName("Given a CREDIT with an amount greater than $10,000.00, when executing, then it does not throw and continues the normal flow")
        void allowsCreditAboveDebitLimit() {
            // given
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            when(provider.execute(any(), any(), any())).thenReturn(aProviderResult());
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            ExecuteTransactionCommand command = aValidCommand(TransactionType.CREDIT, new BigDecimal("50000.00"));

            // when
            // then
            assertThatCode(() -> service.execute(command)).doesNotThrowAnyException();
            verify(provider, times(1)).execute(any(), any(), any());
        }

        @Test
        @DisplayName("Given a currency other than MXN, when executing, then it throws UnsupportedCurrencyException and the provider is never invoked")
        void rejectsUnsupportedCurrency() {
            // given
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            ExecuteTransactionCommand command = aCommand(
                    IDEMPOTENCY_KEY, ACCOUNT_ID, TransactionType.CREDIT, AMOUNT, "USD", DESCRIPTION);

            // when
            // then
            assertThatThrownBy(() -> service.execute(command))
                    .isInstanceOf(UnsupportedCurrencyException.class);
            verify(provider, never()).execute(any(), any(), any());
        }

        @Test
        @DisplayName("Given business rule validation fails, when executing, then repository.save is never invoked")
        void neverSavesOnValidationFailure() {
            // given
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            ExecuteTransactionCommand command = aValidCommand(TransactionType.DEBIT, new BigDecimal("10000.01"));

            // when
            assertThatThrownBy(() -> service.execute(command))
                    .isInstanceOf(DebitLimitExceededException.class);

            // then
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Provider outcomes")
    class ProviderOutcomes {

        @Test
        @DisplayName("Given the provider responds successfully, when executing, then the persisted Transaction has status EXECUTED with the provider's data and a null failureReason")
        void persistsExecutedTransaction() {
            // given
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            when(provider.execute(any(), any(), any())).thenReturn(aProviderResult());
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

            ExecuteTransactionCommand command = aValidCommand();

            // when
            service.execute(command);

            // then
            Transaction saved = captor.getValue();
            assertThat(saved.status()).isEqualTo(TransactionStatus.EXECUTED);
            assertThat(saved.providerTransactionId()).isEqualTo("prov-999");
            assertThat(saved.balanceAfter()).isEqualByComparingTo("450.00");
            assertThat(saved.failureReason()).isNull();
        }

        @Test
        @DisplayName("Given the provider throws ProviderRejectedException, when executing, then the persisted Transaction has status REJECTED with no provider data and a null failureReason")
        void persistsRejectedTransaction() {
            // given
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            when(provider.execute(any(), any(), any()))
                    .thenThrow(new ProviderRejectedException("INSUFFICIENT_FUNDS", "Account balance too low"));
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

            ExecuteTransactionCommand command = aValidCommand();

            // when
            service.execute(command);

            // then
            Transaction saved = captor.getValue();
            assertThat(saved.status()).isEqualTo(TransactionStatus.REJECTED);
            assertThat(saved.providerTransactionId()).isNull();
            assertThat(saved.balanceAfter()).isNull();
            assertThat(saved.failureReason()).isNull();
        }

        @Test
        @DisplayName("Given the provider throws ProviderTimeoutException, when executing, then it saves a FAILED transaction with a populated failureReason and re-throws the exception")
        void savesFailedTransactionAndRethrowsOnTimeout() {
            // given
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            when(provider.execute(any(), any(), any()))
                    .thenThrow(new ProviderTimeoutException("Provider did not respond within the socket timeout"));
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

            ExecuteTransactionCommand command = aValidCommand();

            // when
            // then
            assertThatThrownBy(() -> service.execute(command))
                    .isInstanceOf(ProviderTimeoutException.class);

            verify(repository, times(1)).save(any());
            Transaction saved = captor.getValue();
            assertThat(saved.status()).isEqualTo(TransactionStatus.FAILED);
            assertThat(saved.failureReason()).isEqualTo("Provider did not respond within the socket timeout");
            assertThat(saved.providerTransactionId()).isNull();
            assertThat(saved.balanceAfter()).isNull();
        }

        @Test
        @DisplayName("Given the provider throws ProviderCommunicationException, when executing, then it saves a FAILED transaction with a populated failureReason and re-throws the exception")
        void savesFailedTransactionAndRethrowsOnCommunicationFailure() {
            // given
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            when(provider.execute(any(), any(), any()))
                    .thenThrow(new ProviderCommunicationException("Circuit breaker is open for the transaction provider"));
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

            ExecuteTransactionCommand command = aValidCommand();

            // when
            // then
            assertThatThrownBy(() -> service.execute(command))
                    .isInstanceOf(ProviderCommunicationException.class);

            verify(repository, times(1)).save(any());
            Transaction saved = captor.getValue();
            assertThat(saved.status()).isEqualTo(TransactionStatus.FAILED);
            assertThat(saved.failureReason()).isEqualTo("Circuit breaker is open for the transaction provider");
            assertThat(saved.providerTransactionId()).isNull();
            assertThat(saved.balanceAfter()).isNull();
        }

        @Test
        @DisplayName("Given any provider outcome, when executing, then repository.save is invoked exactly once with the Transaction built from the command")
        void savesExactlyOnceWithMatchingTransaction() {
            // given
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            when(provider.execute(any(), any(), any())).thenReturn(aProviderResult());
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

            ExecuteTransactionCommand command = aValidCommand();

            // when
            service.execute(command);

            // then
            verify(repository, times(1)).save(any());
            Transaction saved = captor.getValue();
            assertThat(saved.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
            assertThat(saved.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(saved.type()).isEqualTo(TransactionType.CREDIT);
            assertThat(saved.money()).isEqualTo(Money.of(AMOUNT, CURRENCY));
            assertThat(saved.description()).isEqualTo(DESCRIPTION);
            assertThat(saved.createdAt()).isEqualTo(FIXED_INSTANT);
        }
    }
}
