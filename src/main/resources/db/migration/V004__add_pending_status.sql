-- Adds PENDING as a valid status: the reservation row written atomically (via
-- INSERT ... ON CONFLICT (idempotency_key) DO NOTHING) before the provider is ever called,
-- to close the concurrent idempotency-key race and give the provider-vs-database consistency
-- gap a durable trace instead of a silent loss. The CHECK constraint must evolve alongside the
-- TransactionStatus enum, or PostgreSQL rejects the very first PENDING insert with a constraint
-- violation.
ALTER TABLE transactions
    DROP CONSTRAINT chk_transactions_status;

ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_status
        CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED'));
