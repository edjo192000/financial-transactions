package com.financial.transactions.challenge.repository;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.service.port.TransactionFilters;
import com.financial.transactions.challenge.service.port.TransactionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTransactionRepository implements TransactionRepository {

    private final JdbcClient jdbcClient;

    public JdbcTransactionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Transaction save(Transaction tx) {
        jdbcClient.sql("""
                INSERT INTO transactions (id, idempotency_key, account_id, type, amount, currency,
                    description, status, provider_transaction_id, balance_after, created_at)
                VALUES (:id, :idempotencyKey, :accountId, :type, :amount, :currency,
                    :description, :status, :providerTransactionId, :balanceAfter, :createdAt)
                """)
                .param("id", tx.id())
                .param("idempotencyKey", tx.idempotencyKey())
                .param("accountId", tx.accountId())
                .param("type", tx.type().name())
                .param("amount", tx.money().amount())
                .param("currency", tx.money().currency())
                .param("description", tx.description())
                .param("status", tx.status().name())
                .param("providerTransactionId", tx.providerTransactionId())
                .param("balanceAfter", tx.balanceAfter())
                .param("createdAt", Timestamp.from(tx.createdAt()))
                .update();

        return tx;
    }

    @Override
    public Optional<Transaction> findById(UUID id) {
        return jdbcClient.sql("SELECT * FROM transactions WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("SELECT * FROM transactions WHERE idempotency_key = :idempotencyKey")
                .param("idempotencyKey", idempotencyKey)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public List<Transaction> findAll(TransactionFilters filters) {
        StringBuilder sql = new StringBuilder("SELECT * FROM transactions WHERE 1=1");

        if (filters.accountId() != null) {
            sql.append(" AND account_id = :accountId");
        }
        if (filters.status() != null) {
            sql.append(" AND status = :status");
        }
        if (filters.type() != null) {
            sql.append(" AND type = :type");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");

        StatementSpec spec = jdbcClient.sql(sql.toString())
                .param("limit", filters.limit())
                .param("offset", filters.page() * filters.limit());

        if (filters.accountId() != null) {
            spec.param("accountId", filters.accountId());
        }
        if (filters.status() != null) {
            spec.param("status", filters.status().name());
        }
        if (filters.type() != null) {
            spec.param("type", filters.type().name());
        }

        return spec.query(this::mapRow).list();
    }

    private Transaction mapRow(ResultSet rs, int rowNum) throws SQLException {
        Money money = new Money(
                rs.getBigDecimal("amount"),
                rs.getString("currency")
        );

        BigDecimal balanceAfter = rs.getBigDecimal("balance_after");

        return new Transaction(
                UUID.fromString(rs.getString("id")),
                rs.getString("idempotency_key"),
                rs.getString("account_id"),
                TransactionType.valueOf(rs.getString("type")),
                money,
                rs.getString("description"),
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getString("provider_transaction_id"),
                balanceAfter,
                rs.getTimestamp("created_at").toInstant()
        );
    }
}