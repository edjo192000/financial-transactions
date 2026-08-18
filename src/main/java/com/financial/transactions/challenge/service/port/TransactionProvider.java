package com.financial.transactions.challenge.service.port;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.TransactionType;

public interface TransactionProvider {

    ProviderResult execute(String accountId, TransactionType type, Money money);
}
