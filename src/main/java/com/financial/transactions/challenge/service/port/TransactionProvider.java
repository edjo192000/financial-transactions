package com.financial.transactions.challenge.service.port;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.TransactionType;

public interface TransactionProvider {

    /**
     * @param idempotencyKey the same key used for our own API's idempotency, forwarded to the
     *                       provider so that repeated calls for the same logical operation —
     *                       whether from Resilience4j's own retry policy, or from us retrying a
     *                       FAILED transaction where the previous outcome is ambiguous — don't
     *                       risk a duplicate execution on the provider's side. The provider is
     *                       expected to recognize a repeated key and return the already-processed
     *                       result instead of executing again.
     */
    ProviderResult execute(String idempotencyKey, String accountId, TransactionType type, Money money);
}
