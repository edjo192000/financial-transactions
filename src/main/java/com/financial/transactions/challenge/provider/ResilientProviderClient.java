package com.financial.transactions.challenge.provider;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.domain.exception.ProviderCommunicationException;
import com.financial.transactions.challenge.domain.exception.ProviderRejectedException;
import com.financial.transactions.challenge.domain.exception.ProviderTimeoutException;
import com.financial.transactions.challenge.provider.dto.ProviderExecuteRequest;
import com.financial.transactions.challenge.provider.dto.ProviderExecuteResponse;
import com.financial.transactions.challenge.provider.dto.ProviderRejectionResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ResilientProviderClient {

    private final RestClient providerRestClient;
    private final ExecutorService providerExecutor;
    private final ProviderProperties properties;

    public ResilientProviderClient(RestClient providerRestClient, ExecutorService providerExecutor,
                                    ProviderProperties properties) {
        this.providerRestClient = providerRestClient;
        this.providerExecutor = providerExecutor;
        this.properties = properties;
    }

    @CircuitBreaker(name = "transactionProvider")
    @Retry(name = "transactionProvider")
    public ProviderExecuteResponse execute(String accountId, TransactionType type, Money money) {
        Callable<ProviderExecuteResponse> callable = () -> callProvider(accountId, type, money);
        Future<ProviderExecuteResponse> future = providerExecutor.submit(callable);

        try {
            long timeoutMillis = properties.providerExecutor().futureTimeout().toMillis();
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ProviderTimeoutException("Provider call did not complete within the executor timeout", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ProviderCommunicationException("Unexpected error while executing provider call", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderCommunicationException("Interrupted while waiting for provider response", e);
        }
    }

    private ProviderExecuteResponse callProvider(String accountId, TransactionType type, Money money) {
        ProviderExecuteRequest request = new ProviderExecuteRequest(accountId, type.name(), money.amount(), money.currency());

        try {
            return providerRestClient.post()
                    .uri("/v1/execute")
                    .body(request)
                    .retrieve()
                    .body(ProviderExecuteResponse.class);
        } catch (RestClientResponseException e) {
            ProviderRejectionResponse rejection = e.getResponseBodyAs(ProviderRejectionResponse.class);
            if (rejection != null) {
                throw new ProviderRejectedException(rejection.code(), rejection.message());
            }
            throw new ProviderCommunicationException("Provider returned an unreadable error response", e);
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw new ProviderTimeoutException("Provider did not respond within the socket timeout", e);
            }
            throw new ProviderCommunicationException("Failed to communicate with provider", e);
        }
    }
}
