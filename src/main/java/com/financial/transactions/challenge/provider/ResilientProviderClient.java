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
    public ProviderExecuteResponse execute(String idempotencyKey, String accountId, TransactionType type,
                                            Money money) {
        Callable<ProviderExecuteResponse> callable = () -> callProvider(idempotencyKey, accountId, type, money);
        Future<ProviderExecuteResponse> future = providerExecutor.submit(callable);

        try {
            long timeoutMillis = properties.providerExecutor().futureTimeout().toMillis();
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // TODO: same ambiguous-outcome case as the SocketTimeoutException branch below in
            // callProvider — see that comment for what querying the provider here would change.
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

    private ProviderExecuteResponse callProvider(String idempotencyKey, String accountId, TransactionType type,
                                                  Money money) {
        ProviderExecuteRequest request = new ProviderExecuteRequest(accountId, type.name(), money.amount(), money.currency());

        try {
            // Same idempotencyKey on every retry of this call — whether triggered by
            // Resilience4j's own @Retry, or by us retrying a previously-FAILED transaction — so
            // the provider can recognize a repeat and avoid executing the operation twice.
            return providerRestClient.post()
                    .uri("/v1/execute")
                    .header("Idempotency-Key", idempotencyKey)
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
                // TODO: a read timeout is ambiguous — the request may have already reached and
                // been processed by the provider, we just never got the response. Right now we
                // just throw and let @Retry fire, which can double-execute against the provider.
                // Sketch below of what this branch would do IF the provider exposed a lookup
                // endpoint. Left commented out because that endpoint is not part of the current
                // provider contract (not in its docs) — nothing here has been verified against it.
                //
                // Optional<ProviderLookupResponse> lookup = lookupProviderTransaction(idempotencyKey);
                //
                // if (lookup.isPresent()) {
                //     ProviderLookupResponse found = lookup.get();
                //     if (found.isTerminal()) {
                //         // The original call *was* received and executed — build the response
                //         // from the lookup instead of throwing, so @Retry never fires and we
                //         // never call POST /v1/execute a second time for this attempt.
                //         return new ProviderExecuteResponse(
                //                 found.transactionId(), found.status(), found.balance(), found.executedAt());
                //     }
                //     if (found.isPending()) {
                //         // Also already received by the provider, just not resolved yet. Must
                //         // still not retry the POST (that would be the duplicate). This needs to
                //         // surface as a distinct, non-retryable outcome the caller polls on —
                //         // mirrors ExecuteTransactionService.waitForResolution(), but against the
                //         // provider instead of our own table.
                //         throw new ProviderStillProcessingException(idempotencyKey);
                //     }
                // }
                // // lookup.isEmpty() (NOT_FOUND) or the lookup call itself failed: the provider
                // // never received the original attempt, so it's safe to fall through and let
                // // @Retry proceed exactly as it does today.
                throw new ProviderTimeoutException("Provider did not respond within the socket timeout", e);
            }
            throw new ProviderCommunicationException("Failed to communicate with provider", e);
        }
    }

    // TODO: hypothetical lookup call for the sketch above — not implemented, the provider does
    // not document an endpoint like this today.
    //
    // private Optional<ProviderLookupResponse> lookupProviderTransaction(String idempotencyKey) {
    //     try {
    //         return Optional.ofNullable(
    //                 providerRestClient.get()
    //                         .uri("/v1/transactions/{idempotencyKey}", idempotencyKey)
    //                         .retrieve()
    //                         .body(ProviderLookupResponse.class));
    //     } catch (RestClientResponseException e) {
    //         if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
    //             return Optional.empty();
    //         }
    //         throw new ProviderCommunicationException("Failed to query provider transaction status", e);
    //     }
    // }
}
