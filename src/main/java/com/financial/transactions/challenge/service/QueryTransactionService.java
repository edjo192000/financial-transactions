package com.financial.transactions.challenge.service;

import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.service.port.TransactionFilters;
import com.financial.transactions.challenge.service.port.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryTransactionService {

    private final TransactionRepository repository;

    public QueryTransactionService(TransactionRepository repository) {
        this.repository = repository;
    }

    public List<Transaction> query(TransactionFilters filters) {
        return repository.findAll(filters);
    }
}
