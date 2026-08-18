package com.financial.transactions.challenge.provider;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.domain.exception.ProviderCommunicationException;
import com.financial.transactions.challenge.provider.dto.ProviderExecuteResponse;
import com.financial.transactions.challenge.service.port.ProviderResult;
import com.financial.transactions.challenge.service.port.TransactionProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.stereotype.Component;

@Component
public class HttpTransactionProvider implements TransactionProvider {

    private final ResilientProviderClient resilientProviderClient;

    public HttpTransactionProvider(ResilientProviderClient resilientProviderClient) {
        this.resilientProviderClient = resilientProviderClient;
    }

    @Override
    public ProviderResult execute(String accountId, TransactionType type, Money money) {
        try {
            ProviderExecuteResponse response = resilientProviderClient.execute(accountId, type, money);
            return new ProviderResult(response.transactionId(), response.balance(), response.executedAt());
        } catch (CallNotPermittedException e) {
            throw new ProviderCommunicationException("Circuit breaker is open for the transaction provider", e);
        }
    }
}
